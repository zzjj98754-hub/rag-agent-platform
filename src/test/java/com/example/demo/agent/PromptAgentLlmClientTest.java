package com.example.demo.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.demo.service.ExternalLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptAgentLlmClientTest {

    private final ExternalLlmClient externalLlmClient = mock(ExternalLlmClient.class);
    private final PromptAgentLlmClient client =
            new PromptAgentLlmClient(externalLlmClient, new ObjectMapper());

    @Test
    void shouldParseStructuredToolCall() {
        when(externalLlmClient.callLlm(anyString(), anyString()))
                .thenReturn("""
                        ```json
                        {"type":"tool_call","toolName":"calculator","arguments":{"expression":"6*7"}}
                        ```
                        """);

        AgentAction action = client.decide(
                "计算 6*7",
                new AgentContext("session-1", List.of()),
                List.of(Map.of("name", "calculator")));

        assertThat(action.type()).isEqualTo(AgentAction.Type.TOOL_CALL);
        assertThat(action.toolName()).isEqualTo("calculator");
        assertThat(action.arguments()).containsEntry("expression", "6*7");
    }

    @Test
    void shouldTreatPlainTextAsFinalAnswerForBackwardCompatibility() {
        when(externalLlmClient.callLlm(anyString(), anyString()))
                .thenReturn("普通模型回答");

        AgentAction action = client.decide(
                "你好",
                new AgentContext("session-1", List.of()),
                List.of());

        assertThat(action.type()).isEqualTo(AgentAction.Type.FINAL_ANSWER);
        assertThat(action.answer()).isEqualTo("普通模型回答");
    }
}
