package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class OpenAiStreamingLlmClientTest {

    private final OpenAiStreamingLlmClient client =
            new OpenAiStreamingLlmClient(
                    WebClient.builder().build(),
                    new ObjectMapper(),
                    "http://localhost/unused",
                    "",
                    "test-model");

    @Test
    void shouldParseOpenAiDeltaContent() {
        String event = """
                {"choices":[{"delta":{"content":"你好"}}]}
                """;

        assertEquals("你好", client.parseToken(event).orElseThrow());
    }

    @Test
    void shouldIgnoreDoneEvent() {
        assertTrue(client.parseToken("data: [DONE]").isEmpty());
    }

    @Test
    void shouldSupportLocalContentEnvelope() {
        assertEquals(
                "token",
                client.parseToken(
                        "data: {\"content\":\"token\"}")
                        .orElseThrow());
    }
}
