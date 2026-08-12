package com.example.demo.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.demo.rag.Bm25Index;
import com.example.demo.rag.HybridRetriever;
import com.example.demo.rag.Reranker;
import com.example.demo.rag.RrfFusion;
import com.example.demo.rag.SearchResult;
import com.example.demo.service.EmbeddingService;
import com.example.demo.service.VectorStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class HybridRetrieverMetricsTest {

    @Test
    void shouldMeasureBm25EmbeddingRetrievalAndRerankStages() {
        Bm25Index bm25Index = mock(Bm25Index.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        VectorStore vectorStore = mock(VectorStore.class);
        Reranker reranker = mock(Reranker.class);

        when(bm25Index.search(eq("Redis"), anyInt()))
                .thenReturn(List.of(new Bm25Index.ScoredDoc(
                        "doc-1",
                        "Redis content",
                        2.0)));
        when(bm25Index.getText("doc-1")).thenReturn("Redis content");
        when(bm25Index.getTextForPrompt("doc-1")).thenReturn("Redis content");
        when(bm25Index.getParentId("doc-1")).thenReturn("doc-1");
        EmbeddingService.EmbeddingVector queryVector =
                new EmbeddingService.EmbeddingVector(
                        new float[] {1.0f},
                        EmbeddingService.EmbeddingSource.LOCAL,
                        "local-test");
        when(vectorStore.embeddingSources()).thenReturn(Set.of(
                EmbeddingService.EmbeddingSource.LOCAL));
        when(embeddingService.embedWithMetadata(
                "Redis",
                EmbeddingService.EmbeddingSource.LOCAL))
                .thenReturn(queryVector);
        when(vectorStore.search(eq(queryVector), anyInt()))
                .thenReturn(List.of(new VectorStore.Result(
                        "doc-1",
                        "Redis content",
                        0.9,
                        null,
                        null)));
        when(reranker.rerank(eq("Redis"), anyList(), anyInt()))
                .thenAnswer(invocation -> {
                    List<SearchResult> candidates = invocation.getArgument(1);
                    return candidates.subList(0, Math.min(3, candidates.size()));
                });

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagObservability observability =
                new RagObservability(new RagMetrics(registry));
        Executor directExecutor = Runnable::run;
        HybridRetriever retriever = new HybridRetriever(
                bm25Index,
                embeddingService,
                vectorStore,
                new RrfFusion(),
                reranker,
                directExecutor,
                observability);

        List<SearchResult> results = observability.observeRequest(
                "Redis",
                () -> retriever.retrieve("Redis", 3));

        assertThat(results).extracting(SearchResult::id).containsExactly("doc-1");
        assertThat(registry.get("rag.bm25.duration").timer().count()).isEqualTo(1);
        assertThat(registry.get("rag.embedding.duration").timer().count()).isEqualTo(1);
        assertThat(registry.get("rag.retrieval.duration").timer().count()).isEqualTo(1);
        assertThat(registry.get("rag.rerank.duration").timer().count()).isEqualTo(1);
    }
}
