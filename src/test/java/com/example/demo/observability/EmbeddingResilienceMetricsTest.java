package com.example.demo.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.demo.service.ResilientEmbeddingService;
import com.example.demo.service.SiliconFlowEmbeddingService;
import com.example.demo.service.SimpleEmbeddingService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class EmbeddingResilienceMetricsTest {

    @Test
    void shouldOpenCircuitOnlyOnceAndFallbackUnderConcurrentFailures()
            throws Exception {
        SiliconFlowEmbeddingService primary =
                mock(SiliconFlowEmbeddingService.class);
        SimpleEmbeddingService fallback = mock(SimpleEmbeddingService.class);
        when(primary.embed(anyString())).thenReturn(new float[0]);
        when(fallback.embed(anyString())).thenReturn(new float[] {1.0f});

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EmbeddingResilienceMetrics metrics =
                new EmbeddingResilienceMetrics(registry);
        ResilientEmbeddingService service =
                new ResilientEmbeddingService(primary, fallback, metrics);
        ReflectionTestUtils.setField(service, "cacheMaxSize", 1000);
        ReflectionTestUtils.setField(service, "circuitThreshold", 3);
        ReflectionTestUtils.setField(service, "circuitCooldownSeconds", 60L);

        int requests = 100;
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(32);
        List<Future<float[]>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < requests; index++) {
                int requestIndex = index;
                futures.add(executor.submit(() -> {
                    startGate.await();
                    return service.embed("query-" + requestIndex);
                }));
            }
            startGate.countDown();
            for (Future<float[]> future : futures) {
                assertThat(future.get()).containsExactly(1.0f);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(service.circuitState()).isEqualTo("OPEN");
        assertThat(registry.get("rag.embedding.circuit.state")
                .gauge()
                .value())
                .isEqualTo(EmbeddingResilienceMetrics.OPEN);
        assertThat(registry.get("rag.embedding.circuit.opens")
                .counter()
                .count())
                .isEqualTo(1);
        assertThat(registry.get("rag.embedding.primary.failures")
                .counter()
                .count())
                .isGreaterThanOrEqualTo(3);
        assertThat(registry.get("rag.embedding.fallback.calls")
                .counter()
                .count())
                .isEqualTo(requests);
    }
}
