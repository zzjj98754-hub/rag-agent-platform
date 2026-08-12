package com.example.demo.agent;

import com.example.demo.agent.tool.ToolSchemaConverter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * OpenAI Compatible Chat Completions Function Calling 客户端。
 */
@Component
@ConditionalOnProperty(
        name = "app.agent.function-calling.enabled",
        havingValue = "true")
public class OpenAiFunctionCallingClient implements AgentLlmClient {

    private static final Logger log =
            LoggerFactory.getLogger(OpenAiFunctionCallingClient.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ToolSchemaConverter schemaConverter;
    private final String url;
    private final String apiKey;
    private final String model;
    private final String thinkingMode;

    public OpenAiFunctionCallingClient(
            @Qualifier("llmRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            ToolSchemaConverter schemaConverter,
            @Value("${app.agent.function-calling.url}")
                    String url,
            @Value("${app.agent.function-calling.api-key}") String apiKey,
            @Value("${app.agent.function-calling.model}") String model,
            @Value("${app.agent.function-calling.thinking-mode}")
                    String thinkingMode) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.schemaConverter = schemaConverter;
        this.url = requireConfigured(url, "AGENT_LLM_URL");
        this.apiKey = requireConfigured(apiKey, "AGENT_LLM_API_KEY");
        this.model = requireConfigured(model, "AGENT_LLM_MODEL");
        this.thinkingMode = normalizeThinkingMode(thinkingMode);
    }

    @Override
    public AgentAction decide(
            String question,
            AgentContext context,
            List<Map<String, Object>> availableTools) {
        Map<String, Object> request =
                buildRequest(question, context, availableTools);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            headers.setBearerAuth(apiKey);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restTemplate.postForObject(
                url,
                new HttpEntity<>(request, headers),
                Map.class);
        return parseResponse(response);
    }

    Map<String, Object> buildRequest(
            String question,
            AgentContext context,
            List<Map<String, Object>> availableTools) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("messages", buildMessages(question, context));
        request.put("tools", schemaConverter.wrapFunctionSchemas(availableTools));
        request.put("tool_choice", "auto");
        request.put("parallel_tool_calls", false);
        if (!"omit".equals(thinkingMode)) {
            request.put("thinking", Map.of("type", thinkingMode));
        }
        return request;
    }

    private String normalizeThinkingMode(String configuredMode) {
        String normalized = configuredMode == null
                ? "omit"
                : configuredMode.trim().toLowerCase();
        if ("omit".equals(normalized)
                || "enabled".equals(normalized)
                || "disabled".equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException(
                "app.agent.function-calling.thinking-mode "
                        + "must be omit, enabled, or disabled");
    }

    private String requireConfigured(String value, String environmentVariable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Agent Function Calling is enabled but "
                            + environmentVariable
                            + " is not configured");
        }
        return value.trim();
    }

    AgentAction parseResponse(Map<String, Object> response) {
        if (response == null) {
            throw new IllegalStateException("Function Calling API 返回空响应");
        }

        JsonNode root = objectMapper.valueToTree(response);
        JsonNode error = root.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new IllegalStateException(
                    "Function Calling API 错误: " + error.path("message").asText(error.toString()));
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException("Function Calling API 响应缺少 choices");
        }
        JsonNode message = choices.get(0).path("message");
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray() && !toolCalls.isEmpty()) {
            if (toolCalls.size() > 1) {
                log.warn(
                        "模型返回多个并行工具调用，当前执行第一个，其余需下一轮重新发起 | count={}",
                        toolCalls.size());
            }
            return parseToolCall(toolCalls.get(0));
        }

        String content = message.path("content").asText("").trim();
        if (content.isBlank()) {
            throw new IllegalStateException("Function Calling API 未返回 tool_calls 或最终答案");
        }
        return AgentAction.finalAnswer(content);
    }

    private AgentAction parseToolCall(JsonNode toolCall) {
        String toolCallId = toolCall.path("id").asText("").trim();
        if (toolCallId.isBlank()) {
            toolCallId = "call_" + UUID.randomUUID().toString().replace("-", "");
        }
        JsonNode function = toolCall.path("function");
        String toolName = function.path("name").asText("").trim();
        if (toolName.isBlank()) {
            throw new IllegalStateException("Function Calling 响应缺少 function.name");
        }

        Map<String, Object> arguments = parseArguments(function.path("arguments"));
        return AgentAction.toolCall(toolCallId, toolName, arguments);
    }

    private Map<String, Object> parseArguments(JsonNode argumentsNode) {
        if (argumentsNode.isMissingNode() || argumentsNode.isNull()) {
            return Map.of();
        }
        try {
            if (argumentsNode.isTextual()) {
                String json = argumentsNode.asText();
                if (json.isBlank()) {
                    return Map.of();
                }
                return objectMapper.readValue(
                        json,
                        new TypeReference<Map<String, Object>>() {});
            }
            return objectMapper.convertValue(
                    argumentsNode,
                    new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalStateException("Function Calling arguments 不是合法 JSON 对象", e);
        }
    }

    private List<Map<String, Object>> buildMessages(
            String question,
            AgentContext context) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of(
                "role", "system",
                "content", "You are a tool-using assistant. Use tools when needed. "
                        + "Return a concise final answer after observing tool results."));

        boolean containsCurrentQuestion = false;
        for (AgentContext.Message message : context.getHistory()) {
            if ("user".equals(message.role())
                    && question.equals(message.content())) {
                containsCurrentQuestion = true;
            }
            messages.add(toOpenAiMessage(message));
        }
        if (!containsCurrentQuestion) {
            messages.add(Map.of("role", "user", "content", question));
        }
        return messages;
    }

    private Map<String, Object> toOpenAiMessage(AgentContext.Message message) {
        if (message.isAssistantToolCall()) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", message.toolName());
            function.put("arguments", writeJson(message.arguments()));

            Map<String, Object> toolCall = new LinkedHashMap<>();
            toolCall.put("id", message.toolCallId());
            toolCall.put("type", "function");
            toolCall.put("function", function);

            Map<String, Object> assistantMessage = new LinkedHashMap<>();
            assistantMessage.put("role", "assistant");
            assistantMessage.put("content", null);
            assistantMessage.put("tool_calls", List.of(toolCall));
            return assistantMessage;
        }
        if (message.isToolObservation()) {
            return Map.of(
                    "role", "tool",
                    "tool_call_id", message.toolCallId(),
                    "content", safeContent(message.content()));
        }
        if ("tool".equals(message.role())) {
            return Map.of(
                    "role", "system",
                    "content", "Historical tool observation: "
                            + safeContent(message.content()));
        }
        return Map.of(
                "role", normalizeRole(message.role()),
                "content", safeContent(message.content()));
    }

    private String normalizeRole(String role) {
        return switch (role == null ? "" : role) {
            case "system", "user", "assistant" -> role;
            default -> "user";
        };
    }

    private String safeContent(String content) {
        return content == null ? "" : content;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("工具参数序列化失败", e);
        }
    }
}
