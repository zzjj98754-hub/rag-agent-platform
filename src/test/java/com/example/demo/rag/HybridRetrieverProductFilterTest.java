package com.example.demo.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.demo.observability.RagMetrics;
import com.example.demo.observability.RagObservability;
import com.example.demo.service.EmbeddingService;
import com.example.demo.service.InMemoryVectorStore;
import com.example.demo.service.SimpleEmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class HybridRetrieverProductFilterTest {

    @Test
    void sameFaultOnlyReturnsTheSelectedModelsDocuments() {
        SimpleEmbeddingService embedding = new SimpleEmbeddingService();
        InMemoryVectorStore vectors = new InMemoryVectorStore(new ObjectMapper(), mock(StringRedisTemplate.class));
        Bm25Index bm25 = new Bm25Index();
        bm25.rebuild(
                java.util.Map.of(
                        "虚构-星河-A100-使用手册.md:0:0", "星河 A100 无法连接时必须使用 2.4GHz Wi-Fi。",
                        "虚构-远山-B200-使用手册.md:0:0", "远山 B200 无法连接时支持 5GHz Wi-Fi 和网线。"),
                java.util.Map.of(), java.util.Map.of());
        embedding.buildVocabulary(List.of(
                "星河 A100 无法连接时必须使用 2.4GHz Wi-Fi。",
                "远山 B200 无法连接时支持 5GHz Wi-Fi 和网线。"));
        List<String> ids = List.of("虚构-星河-A100-使用手册.md:0:0", "虚构-远山-B200-使用手册.md:0:0");
        for (String id : ids) {
            String text = bm25.getText(id);
            vectors.add(id, text, embedding.embedWithMetadata(text), id, text);
        }
        Executor direct = Runnable::run;
        HybridRetriever retriever = new HybridRetriever(bm25, embedding, vectors, new RrfFusion(),
                (query, documents, topK) -> documents, direct,
                new RagObservability(new RagMetrics(new SimpleMeterRegistry())));

        assertThat(retriever.retrieve("无法连接 Wi-Fi", 3,
                Set.of("虚构-星河-A100-使用手册.md")))
                .extracting(SearchResult::id)
                .allMatch(id -> id.startsWith("虚构-星河-A100-使用手册.md"));
    }
}
