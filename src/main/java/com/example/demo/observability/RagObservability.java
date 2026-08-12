package com.example.demo.observability;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * RAG 请求级观测入口，负责 ThreadLocal 生命周期、阶段指标和汇总日志。
 */
@Component
public class RagObservability {

    private static final Logger log = LoggerFactory.getLogger(RagObservability.class);
    private static final int MAX_LOG_QUERY_LENGTH = 500;

    private final ThreadLocal<RagRequestObservation> current = new ThreadLocal<>();
    private final RagMetrics metrics;

    public RagObservability(RagMetrics metrics) {
        this.metrics = metrics;
    }

    public <T> T observeRequest(String query, Supplier<T> action) {
        RagRequestObservation observation = beginRequest(query);
        try {
            return withObservation(observation, action);
        } finally {
            completeRequest(observation);
        }
    }

    public RagRequestObservation beginRequest(String query) {
        return new RagRequestObservation(query);
    }

    public <T> T withObservation(
            RagRequestObservation observation,
            Supplier<T> action) {
        RagRequestObservation previous = current.get();
        current.set(observation);
        try {
            return action.get();
        } catch (RuntimeException e) {
            observation.markFailure();
            throw e;
        } finally {
            if (previous == null) {
                current.remove();
            } else {
                current.set(previous);
            }
        }
    }

    public void completeRequest(
            RagRequestObservation observation) {
        complete(observation);
    }

    public void markFailure(RagRequestObservation observation) {
        if (observation != null) {
            observation.markFailure();
        }
    }

    public RagRequestObservation currentObservation() {
        return current.get();
    }

    public <T> T measure(
            RagRequestObservation observation,
            RagStage stage,
            Supplier<T> action) {
        long start = System.nanoTime();
        try {
            return action.get();
        } finally {
            recordDuration(observation, stage, System.nanoTime() - start);
        }
    }

    public void recordDuration(
            RagRequestObservation observation,
            RagStage stage,
            long durationNanos) {
        metrics.recordDuration(stage, durationNanos);
        if (observation != null) {
            observation.addDuration(stage, durationNanos);
        }
    }

    public void recordLlm(long durationNanos, long tokens) {
        recordLlm(durationNanos, tokens, "success");
    }

    public void recordLlm(long durationNanos, long tokens, String outcome) {
        recordLlm(
                current.get(),
                durationNanos,
                tokens,
                outcome);
    }

    public void recordLlm(
            RagRequestObservation observation,
            long durationNanos,
            long tokens,
            String outcome) {
        recordDuration(observation, RagStage.LLM, durationNanos);
        metrics.recordTokens(tokens);
        metrics.recordLlmCall(outcome);
        if (observation != null) {
            observation.addTokens(tokens);
            observation.recordLlmCall(outcome);
        }
    }

    public void markCacheHit() {
        RagRequestObservation observation = current.get();
        if (observation != null) {
            observation.markCacheHit();
        }
    }

    private void complete(RagRequestObservation observation) {
        long totalNanos = observation.elapsedNanos();
        observation.addDuration(RagStage.TOTAL, totalNanos);
        metrics.recordDuration(RagStage.TOTAL, totalNanos);
        metrics.recordRequest(observation.outcome());
        log.info(
                "RAG_METRICS query=\"{}\" retrieval_time={}ms bm25_time={}ms "
                        + "embedding_time={}ms rerank_time={}ms llm_time={}ms "
                        + "total_time={}ms tokens={} llm_outcome={} "
                        + "cache_hit={} outcome={}",
                sanitizeQuery(observation.query()),
                observation.durationMillis(RagStage.RETRIEVAL),
                observation.durationMillis(RagStage.BM25),
                observation.durationMillis(RagStage.EMBEDDING),
                observation.durationMillis(RagStage.RERANK),
                observation.durationMillis(RagStage.LLM),
                observation.durationMillis(RagStage.TOTAL),
                observation.tokenCount(),
                observation.llmOutcome(),
                observation.cacheHit(),
                observation.outcome());
    }

    private String sanitizeQuery(String query) {
        if (query == null) {
            return "";
        }
        String sanitized = query
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('"', '\'');
        return sanitized.length() <= MAX_LOG_QUERY_LENGTH
                ? sanitized
                : sanitized.substring(0, MAX_LOG_QUERY_LENGTH) + "...";
    }
}
