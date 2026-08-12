package com.example.demo.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

import com.example.demo.observability.RagMetrics;
import com.example.demo.observability.RagObservability;
import com.example.demo.persistence.service.DocumentPersistenceService;
import com.example.demo.service.DocumentIngestionService;
import com.example.demo.service.DocumentRegistry;
import com.example.demo.service.EmbeddingService;
import com.example.demo.service.InMemoryVectorStore;
import com.example.demo.service.SimpleEmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.StringRedisTemplate;

class LocalHybridRagIntegrationTest {

    @TempDir
    Path docsDirectory;

    @Test
    void ingestionBuildsBm25AndLocalVectorsAndHybridUsesBothRoutes()
            throws Exception {
        String parent = ("Redis 使用跳表实现有序集合范围查询，"
                + "BM25 负责关键词召回，向量检索负责语义召回。\n")
                .repeat(55);
        Files.writeString(
                docsDirectory.resolve("redis-guide.txt"),
                parent,
                StandardCharsets.UTF_8);

        SimpleEmbeddingService localEmbedding =
                new SimpleEmbeddingService();
        Bm25Index bm25Index = new Bm25Index();
        DocumentRegistry registry = new DocumentRegistry();
        InMemoryVectorStore vectorStore = new InMemoryVectorStore(
                new ObjectMapper(),
                mock(StringRedisTemplate.class));
        DocumentPersistenceService persistence =
                mock(DocumentPersistenceService.class);
        DocumentIngestionService ingestion = new DocumentIngestionService(
                new HierarchicalChunker(),
                localEmbedding,
                vectorStore,
                bm25Index,
                registry,
                persistence,
                false,
                docsDirectory.toString());

        ingestion.ingestConfiguredDocuments();

        assertThat(registry.getAllChunkTexts()).isNotEmpty();
        assertThat(bm25Index.size()).isGreaterThan(0);
        assertThat(vectorStore.size()).isGreaterThan(0);
        assertThat(vectorStore.embeddingSources()).containsExactly(
                EmbeddingService.EmbeddingSource.LOCAL);
        assertThat(registry.getDocumentSnapshots()
                        .get("redis-guide.txt")
                        .embeddingSource())
                .isEqualTo("local");

        String query = "Redis 跳表范围查询";
        assertThat(bm25Index.search(query, 5)).isNotEmpty();
        assertThat(vectorStore.search(
                        localEmbedding.embedWithMetadata(query),
                        5))
                .isNotEmpty();

        Reranker rrfFallback = (q, documents, topK) ->
                documents.subList(0, Math.min(topK, documents.size()));
        RagObservability observability = new RagObservability(
                new RagMetrics(new SimpleMeterRegistry()));
        Executor directExecutor = Runnable::run;
        HybridRetriever retriever = new HybridRetriever(
                bm25Index,
                localEmbedding,
                vectorStore,
                new RrfFusion(),
                rrfFallback,
                directExecutor,
                observability);

        List<SearchResult> results = retriever.retrieve(query, 3);

        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(result ->
                result.scoreType() == RerankResult.ScoreType.RRF);
        assertThat(results).anyMatch(result ->
                result.parentText() != null
                        && result.parentText().length()
                                >= result.text().length());
        assertThat(new RelevanceGate(0.35, 0.01)
                        .evaluate(results)
                        .effectiveDocs())
                .isNotEmpty();
    }
}
