package com.example.demo.observability;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 单个 RAG 请求的阶段数据。阶段可由并行检索线程安全地回写。
 */
public final class RagRequestObservation {

    private final String query;
    private final long startedNanos = System.nanoTime();
    private final Map<RagStage, AtomicLong> stageNanos =
            new EnumMap<>(RagStage.class);
    private final LongAdder tokenCount = new LongAdder();
    private final LongAdder llmCalls = new LongAdder();
    private final LongAdder llmFallbackCalls = new LongAdder();
    private volatile boolean cacheHit;
    private volatile String outcome = "success";

    public RagRequestObservation(String query) {
        this.query = query;
        for (RagStage stage : RagStage.values()) {
            stageNanos.put(stage, new AtomicLong());
        }
    }

    public String query() {
        return query;
    }

    public void addDuration(RagStage stage, long durationNanos) {
        stageNanos.get(stage).addAndGet(Math.max(0, durationNanos));
    }

    public long durationNanos(RagStage stage) {
        return stageNanos.get(stage).get();
    }

    public long durationMillis(RagStage stage) {
        return durationNanos(stage) / 1_000_000;
    }

    public long elapsedNanos() {
        return System.nanoTime() - startedNanos;
    }

    public void addTokens(long tokens) {
        tokenCount.add(Math.max(0, tokens));
    }

    public long tokenCount() {
        return tokenCount.sum();
    }

    public void recordLlmCall(String llmOutcome) {
        llmCalls.increment();
        if ("fallback".equals(llmOutcome)) {
            llmFallbackCalls.increment();
        }
    }

    public String llmOutcome() {
        if (llmCalls.sum() == 0) {
            return "not_called";
        }
        return llmFallbackCalls.sum() > 0 ? "fallback" : "success";
    }

    public boolean cacheHit() {
        return cacheHit;
    }

    public void markCacheHit() {
        cacheHit = true;
    }

    public String outcome() {
        return outcome;
    }

    public void markFailure() {
        outcome = "failure";
    }
}
