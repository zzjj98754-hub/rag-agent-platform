package com.example.demo.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RagObservabilityTest {

    @Test
    void shouldRecordRequestStagesAndTokenCount() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagObservability observability =
                new RagObservability(new RagMetrics(registry));

        String result = observability.observeRequest("Redis 是什么", () -> {
            RagRequestObservation request = observability.currentObservation();
            observability.recordDuration(
                    request,
                    RagStage.BM25,
                    TimeUnit.MILLISECONDS.toNanos(2));
            observability.recordDuration(
                    request,
                    RagStage.EMBEDDING,
                    TimeUnit.MILLISECONDS.toNanos(5));
            observability.recordDuration(
                    request,
                    RagStage.RETRIEVAL,
                    TimeUnit.MILLISECONDS.toNanos(7));
            observability.recordDuration(
                    request,
                    RagStage.RERANK,
                    TimeUnit.MILLISECONDS.toNanos(3));
            observability.recordLlm(
                    TimeUnit.MILLISECONDS.toNanos(20),
                    42,
                    "fallback");
            assertThat(request.llmOutcome()).isEqualTo("fallback");
            return "answer";
        });

        assertThat(result).isEqualTo("answer");
        assertThat(observability.currentObservation()).isNull();
        assertThat(registry.get("rag.bm25.duration").timer().count()).isEqualTo(1);
        assertThat(registry.get("rag.embedding.duration").timer().count()).isEqualTo(1);
        assertThat(registry.get("rag.rerank.duration").timer().count()).isEqualTo(1);
        assertThat(registry.get("rag.llm.duration").timer().count()).isEqualTo(1);
        assertThat(registry.get("rag.request.duration").timer().count()).isEqualTo(1);
        assertThat(registry.get("rag.llm.tokens").summary().totalAmount())
                .isEqualTo(42);
        assertThat(registry.get("rag.llm.calls")
                .tag("outcome", "fallback")
                .counter()
                .count())
                .isEqualTo(1);
        assertThat(registry.get("rag.requests")
                .tag("outcome", "success")
                .counter()
                .count())
                .isEqualTo(1);
    }
}
