package com.example.demo.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Embedding 主备切换与熔断指标。
 *
 * <p>熔断状态使用数值 Gauge，避免把请求级信息放进标签：
 * 0=CLOSED、1=HALF_OPEN、2=OPEN。
 */
@Component
public class EmbeddingResilienceMetrics {

    public static final int CLOSED = 0;
    public static final int HALF_OPEN = 1;
    public static final int OPEN = 2;

    private final AtomicInteger circuitState = new AtomicInteger(CLOSED);
    private final Counter primaryFailures;
    private final Counter fallbackCalls;
    private final Counter circuitOpens;

    public EmbeddingResilienceMetrics(MeterRegistry meterRegistry) {
        Gauge.builder(
                        "rag.embedding.circuit.state",
                        circuitState,
                        AtomicInteger::get)
                .description("Embedding circuit state: 0=CLOSED, 1=HALF_OPEN, 2=OPEN")
                .register(meterRegistry);
        primaryFailures = Counter.builder("rag.embedding.primary.failures")
                .description("Embedding primary service failure count")
                .register(meterRegistry);
        fallbackCalls = Counter.builder("rag.embedding.fallback.calls")
                .description("Embedding fallback invocation count")
                .register(meterRegistry);
        circuitOpens = Counter.builder("rag.embedding.circuit.opens")
                .description("Embedding circuit open transition count")
                .register(meterRegistry);
    }

    public void setClosed() {
        circuitState.set(CLOSED);
    }

    public void setHalfOpen() {
        circuitState.set(HALF_OPEN);
    }

    public void setOpen() {
        circuitState.set(OPEN);
    }

    public void recordPrimaryFailure() {
        primaryFailures.increment();
    }

    public void recordFallbackCall() {
        fallbackCalls.increment();
    }

    public void recordCircuitOpen() {
        circuitOpens.increment();
    }
}
