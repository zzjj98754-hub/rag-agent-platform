package com.example.demo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

import com.example.demo.dto.IngestionResult;
import com.example.demo.dto.IngestionStatus.State;
import com.example.demo.persistence.service.DocumentPersistenceService;
import com.example.demo.persistence.entity.OutboxEventEntity;
import com.example.demo.persistence.mapper.OutboxEventMapper;
import com.example.demo.persistence.service.OutboxEventService;
import com.example.demo.persistence.service.OutboxRelay;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DocumentIndexTaskServiceTest {
    private static final String PAYLOAD = """
            {"fileName":"knowledge.md","filePath":"upload://knowledge.md",
             "content":"Redis","creatorId":7,"taskId":"task-1"}
            """;

    private DocumentIngestionService ingestion;
    private ElasticsearchChunkIndexer elasticsearch;
    private DocumentRegistry registry;
    private DocumentIndexTaskService tasks;
    private DocumentPersistenceService documents;

    @BeforeEach
    void setUp() {
        ingestion = mock(DocumentIngestionService.class);
        elasticsearch = mock(ElasticsearchChunkIndexer.class);
        registry = mock(DocumentRegistry.class);
        documents = mock(DocumentPersistenceService.class);
        when(documents.claimTask(eq("task-1"), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        tasks = new DocumentIndexTaskService(documents,
                ingestion, new ObjectMapper(), elasticsearch, registry);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldConsumeWithOrWithoutLocalTask(boolean hasLocalTask) {
        String taskId = hasLocalTask ? "task-1" : null;
        IngestionResult result = new IngestionResult(List.of("knowledge.md"), List.of(), List.of(), 1, 1);
        when(ingestion.ingestOne("knowledge.md", "Redis", 7L)).thenReturn(result);
        when(registry.getChunkMetadata("knowledge.md")).thenReturn(Map.of());

        tasks.consumeJson(PAYLOAD);

        verify(ingestion).ingestOne("knowledge.md", "Redis", 7L);
        verify(registry).getChunkMetadata("knowledge.md");
        verify(documents).finishTask("task-1", "SUCCEEDED", null, null);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldPropagateIngestionFailureWithOrWithoutLocalTask(boolean hasLocalTask) {
        String taskId = hasLocalTask ? "task-1" : null;
        IllegalStateException failure = new IllegalStateException("index unavailable");
        when(ingestion.ingestOne("knowledge.md", "Redis", 7L)).thenThrow(failure);

        assertThatThrownBy(() -> tasks.consumeJson(PAYLOAD)).isSameAs(failure);

        verify(ingestion).ingestOne("knowledge.md", "Redis", 7L);
        verifyNoInteractions(registry, elasticsearch);
        verify(documents).finishTask("task-1", "FAILED", "INDEX_ERROR", "index unavailable");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRejectFailedResultWithOrWithoutLocalTask(boolean hasLocalTask) {
        String taskId = hasLocalTask ? "task-1" : null;
        when(ingestion.ingestOne("knowledge.md", "Redis", 7L)).thenReturn(failedResult());

        assertThatThrownBy(() -> tasks.consumeJson(PAYLOAD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("文档入库失败: 切分失败");

        verifyNoInteractions(registry, elasticsearch);
        verify(documents).finishTask("task-1", "FAILED", "INDEX_ERROR", "文档入库失败: 切分失败");
    }

    @Test
    void shouldCompleteSkippedResult() {
        String taskId = "task-1";
        IngestionResult result = new IngestionResult(List.of(), List.of("knowledge.md"), List.of(), 0, 1);
        when(ingestion.ingestOne("knowledge.md", "Redis", 7L)).thenReturn(result);
        when(registry.getChunkMetadata("knowledge.md")).thenReturn(Map.of());

        tasks.consumeJson(PAYLOAD);

        verify(documents).finishTask("task-1", "SUCCEEDED", null, null);
    }

    @Test
    void localOutboxShouldRetryFailedResultInsteadOfMarkingProcessed() {
        OutboxEventMapper outbox = mock(OutboxEventMapper.class);
        OutboxEventEntity event = new OutboxEventEntity();
        event.setId(9L);
        event.setRetryCount(0);
        event.setEventType(OutboxEventService.DOCUMENT_INDEX_REQUESTED);
        event.setPayload(PAYLOAD);
        when(outbox.findPending(50)).thenReturn(List.of(event));
        when(outbox.claim(org.mockito.ArgumentMatchers.eq(9L), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(60))).thenReturn(1);
        when(ingestion.ingestOne("knowledge.md", "Redis", 7L)).thenReturn(failedResult());

        new OutboxRelay(outbox, mock(ChatCacheProjector.class),
                new LocalIndexEventPublisher(tasks), 50, 8, 5).relay();

        verify(outbox).markRetry(9L, "文档入库失败: 切分失败", 5, 8);
        verify(outbox, never()).markProcessed(9L);
        verifyNoInteractions(registry, elasticsearch);
    }

    private IngestionResult failedResult() {
        return new IngestionResult(List.of(), List.of(),
                List.of(new IngestionResult.FailedDoc("knowledge.md", "切分失败")), 0, 1);
    }
}
