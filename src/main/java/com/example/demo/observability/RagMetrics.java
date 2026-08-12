package com.example.demo.observability;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * 低基数 Micrometer 指标，禁止将 query、sessionId、traceId 作为标签。
 */
@Component
public class RagMetrics {

    private final MeterRegistry meterRegistry;
    private final Map<RagStage, Timer> timers = new EnumMap<>(RagStage.class);
    private final DistributionSummary tokenSummary;

    public RagMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        for (RagStage stage : RagStage.values()) {
            timers.put(stage, Timer.builder(stage.metricName())
                    .description("RAG " + stage.name().toLowerCase() + " duration")
                    .publishPercentileHistogram()
                    .serviceLevelObjectives(
                            Duration.ofMillis(50),
                            Duration.ofMillis(100),
                            Duration.ofMillis(500),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(5))
                    .register(meterRegistry));
        }
        tokenSummary = DistributionSummary.builder("rag.llm.tokens")
                .description("LLM token count per call")
                .baseUnit("tokens")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    public void recordDuration(RagStage stage, long durationNanos) {
        timers.get(stage).record(Math.max(0, durationNanos), TimeUnit.NANOSECONDS);
    }

    public void recordTokens(long tokens) {
        tokenSummary.record(Math.max(0, tokens));
    }

    public void recordLlmCall(String outcome) {
        meterRegistry.counter(
                "rag.llm.calls",
                "outcome",
                "fallback".equals(outcome) ? "fallback" : "success")
                .increment();
    }

    public void recordRequest(String outcome) {
        meterRegistry.counter(
                "rag.requests",
                "outcome",
                "failure".equals(outcome) ? "failure" : "success")
                .increment();
    }
}
