package com.example.demo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.demo.observability.RagMetrics;
import com.example.demo.observability.RagObservability;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.SocketTimeoutException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

class ExternalLlmClientMetricsTest {

    @Test
    void shouldPreferProviderReportedTotalTokens() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagObservability observability =
                new RagObservability(new RagMetrics(registry));
        ExternalLlmClient client = new ExternalLlmClient(
                restTemplate,
                "https://llm.example/chat",
                observability);
        when(restTemplate.postForObject(
                eq("https://llm.example/chat"),
                any(),
                eq(Map.class)))
                .thenReturn(Map.of(
                        "content", "answer",
                        "usage", Map.of("total_tokens", 123)));

        String answer = observability.observeRequest(
                "question",
                () -> client.callLlm("prompt", "model"));

        assertThat(answer).isEqualTo("answer");
        assertThat(registry.get("rag.llm.duration").timer().count()).isEqualTo(1);
        assertThat(registry.get("rag.llm.tokens").summary().totalAmount())
                .isEqualTo(123);
        assertThat(registry.get("rag.llm.calls")
                .tag("outcome", "success")
                .counter()
                .count())
                .isEqualTo(1);
    }

    @Test
    void shouldRecordFallbackWhenProviderTimesOut() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagObservability observability =
                new RagObservability(new RagMetrics(registry));
        ExternalLlmClient client = new ExternalLlmClient(
                restTemplate,
                "https://llm.example/chat",
                observability);
        when(restTemplate.postForObject(
                eq("https://llm.example/chat"),
                any(),
                eq(Map.class)))
                .thenThrow(new ResourceAccessException(
                        "read timed out",
                        new SocketTimeoutException("read timed out")));

        String answer = observability.observeRequest(
                "question",
                () -> client.callLlm("prompt", "model"));

        assertThat(answer).isNotBlank();
        assertThat(registry.get("rag.llm.calls")
                .tag("outcome", "fallback")
                .counter()
                .count())
                .isEqualTo(1);
    }
}
