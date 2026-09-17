package com.example.demo.service;

import com.example.demo.dto.IngestionResult;
import com.example.demo.dto.IngestionStatus;
import com.example.demo.persistence.service.DocumentPersistenceService;
import com.example.demo.persistence.service.DocumentChunkPersistenceService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Consumer-side index command handler. Kafka is only a transport: keeping this handler
 * independent lets a laptop run the exact same command through the Outbox local fallback.
 */
@Service
public class DocumentIndexTaskService {
    private final DocumentPersistenceService documents;
    private final DocumentIngestionService ingestion;
    private final ObjectMapper mapper;
    private final ElasticsearchChunkIndexer elasticsearch;
    private final DocumentRegistry registry;
    private final DocumentChunkPersistenceService chunks;
    private final String workerId = java.util.UUID.randomUUID().toString();

    @org.springframework.beans.factory.annotation.Autowired
    public DocumentIndexTaskService(DocumentPersistenceService documents,
            DocumentIngestionService ingestion, ObjectMapper mapper, ElasticsearchChunkIndexer elasticsearch,
            DocumentRegistry registry, DocumentChunkPersistenceService chunks) {
        this.documents = documents;
        this.ingestion = ingestion;
        this.mapper = mapper;
        this.elasticsearch = elasticsearch;
        this.registry = registry;
        this.chunks = chunks;
    }

    /** Compatibility constructor for unit tests that do not use JDBC chunk persistence. */
    public DocumentIndexTaskService(DocumentPersistenceService documents, DocumentIngestionService ingestion,
            ObjectMapper mapper, ElasticsearchChunkIndexer elasticsearch, DocumentRegistry registry) {
        this(documents, ingestion, mapper, elasticsearch, registry, null);
    }

    public String request(String fileName, String content, Long creatorId) {
        String taskId = UUID.randomUUID().toString();
        String filePath = "upload://" + fileName;
        documents.requestAsyncIndex(taskId, fileName, filePath, content, creatorId);
        return taskId;
    }

    public IngestionStatus status(String taskId) {
        var task = documents.findTask(taskId);
        if (task == null) throw new IllegalArgumentException("索引任务不存在: " + taskId);
        IngestionStatus status = new IngestionStatus(task.getTaskId());
        status.restore(task.getStatus(), task.getRetryCount(), task.getFailureCode(), task.getFailureReason(), task.getCreatedAt(), task.getStartedAt(), task.getFinishedAt());
        return status;
    }

    public com.example.demo.persistence.entity.IndexTaskEntity findTask(String taskId) {
        return documents.findTask(taskId);
    }

    public int chunkCount(long documentId) { return chunks == null ? 0 : chunks.count(documentId); }

    public boolean retry(String taskId) { return documents.retryTask(taskId); }

    public void consumeJson(String payload) {
        try {
            consume(mapper.readValue(payload, new TypeReference<>() {}));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("索引任务消息格式错误", e);
        }
    }

    public void consume(Map<String, Object> payload) {
        String fileName = String.valueOf(payload.get("fileName"));
        String filePath = String.valueOf(payload.get("filePath"));
        String content = String.valueOf(payload.get("content"));
        Long creatorId = ((Number) payload.getOrDefault("creatorId", -1L)).longValue();
        // Replayed or cross-instance messages may have no local progress record.
        String taskId = String.valueOf(payload.get("taskId"));
        if (payload.get("taskId") == null) {
            // Legacy chat/outbox fixtures do not represent persisted index tasks.
            // Production document events always carry taskId and use the DB claim below.
            taskId = null;
        } else if (!documents.claimTask(taskId, workerId)) return;
        try {
            IngestionResult result = ingestion.ingestOne(fileName, content, creatorId < 0 ? null : creatorId);
            if (!result.getFailed().isEmpty()) {
                throw new IllegalStateException("文档入库失败: " + result.getFailed().get(0).reason());
            }
            registry.getChunkMetadata(fileName).forEach(elasticsearch::index);
            if (taskId != null) { documents.finishTask(taskId, "SUCCEEDED", null, null); documents.markIndexed(filePath); }
        } catch (RuntimeException ex) {
            if (taskId != null) { documents.finishTask(taskId, "FAILED", "INDEX_ERROR", ex.getMessage()); documents.markFailed(filePath); }
            throw ex;
        }
    }
}
