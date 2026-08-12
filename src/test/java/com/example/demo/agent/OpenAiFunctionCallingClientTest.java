package com.example.demo.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.demo.agent.tool.ToolSchemaConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class OpenAiFunctionCallingClientTest {

    private static final String URL = "https://llm.example/v1/chat/completions";

    private MockRestServiceServer server;
    private OpenAiFunctionCallingClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        client = new OpenAiFunctionCallingClient(
                restTemplate,
                new ObjectMapper(),
                new ToolSchemaConverter(),
                URL,
                "test-key",
                "test-model",
                "disabled");
    }

    @Test
    void shouldSendOpenAiToolsAndParseToolCall() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("test-model"))
                .andExpect(jsonPath("$.thinking.type").value("disabled"))
                .andExpect(jsonPath("$.tool_choice").value("auto"))
                .andExpect(jsonPath("$.tools[0].type").value("function"))
                .andExpect(jsonPath("$.tools[0].function.name").value("searchKnowledge"))
                .andRespond(withSuccess("""
                        {
                          "choices": [{
                            "message": {
                              "role": "assistant",
                              "content": null,
                              "tool_calls": [{
                                "id": "call_abc",
                                "type": "function",
                                "function": {
                                  "name": "searchKnowledge",
                                  "arguments": "{\\"query\\":\\"Redis\\"}"
                                }
                              }]
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        AgentContext context = new AgentContext(
                "session-1",
                List.of(new AgentContext.Message("user", "Redis 是什么")));
        AgentAction action = client.decide(
                "Redis 是什么",
                context,
                List.of(functionSchema()));

        server.verify();
        assertThat(action.type()).isEqualTo(AgentAction.Type.TOOL_CALL);
        assertThat(action.toolCallId()).isEqualTo("call_abc");
        assertThat(action.toolName()).isEqualTo("searchKnowledge");
        assertThat(action.arguments()).containsEntry("query", "Redis");
    }

    @Test
    void shouldReturnObservationWithMatchingToolCallIdOnNextRound() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.messages[2].role").value("assistant"))
                .andExpect(jsonPath("$.messages[2].tool_calls[0].id").value("call_abc"))
                .andExpect(jsonPath("$.messages[3].role").value("tool"))
                .andExpect(jsonPath("$.messages[3].tool_call_id").value("call_abc"))
                .andExpect(jsonPath("$.messages[3].content").value("Redis 文档结果"))
                .andRespond(withSuccess("""
                        {
                          "choices": [{
                            "message": {
                              "role": "assistant",
                              "content": "Redis 是一个高性能键值数据库。"
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        AgentContext context = new AgentContext(
                "session-1",
                List.of(new AgentContext.Message("user", "Redis 是什么")));
        context.addAssistantToolCall(
                "call_abc",
                "searchKnowledge",
                Map.of("query", "Redis"));
        context.addToolObservation(
                "call_abc",
                "searchKnowledge",
                "Redis 文档结果");

        AgentAction action = client.decide(
                "Redis 是什么",
                context,
                List.of(functionSchema()));

        server.verify();
        assertThat(action.type()).isEqualTo(AgentAction.Type.FINAL_ANSWER);
        assertThat(action.answer()).isEqualTo("Redis 是一个高性能键值数据库。");
    }

    @Test
    void shouldFailFastWhenFunctionCallingApiKeyIsMissing() {
        assertThatThrownBy(() -> new OpenAiFunctionCallingClient(
                        new RestTemplate(),
                        new ObjectMapper(),
                        new ToolSchemaConverter(),
                        URL,
                        " ",
                        "test-model",
                        "disabled"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AGENT_LLM_API_KEY");
    }

    private Map<String, Object> functionSchema() {
        return Map.of(
                "name", "searchKnowledge",
                "description", "搜索知识库",
                "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string")),
                        "required", List.of("query")));
    }
}
