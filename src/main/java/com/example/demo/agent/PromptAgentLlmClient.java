package com.example.demo.agent;

import com.example.demo.agent.tool.ToolScheduler.ToolCallRecord;
import com.example.demo.service.LlmClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 基于结构化 JSON 协议的 Agent LLM 适配器。
 *
 * <p>生产环境可将底层 {@link LlmClient} 替换成原生 Function Calling 客户端，
 * AgentExecutor 无需改变。
 */
@Component
@ConditionalOnProperty(
        name = "app.agent.function-calling.enabled",
        havingValue = "false",
        matchIfMissing = true)
public class PromptAgentLlmClient implements AgentLlmClient {

    static final String PROTOCOL_MARKER = "AGENT_DECISION_PROTOCOL_V1";
    private static final int MAX_MESSAGE_CHARS = 4_000;
    private static final int MAX_OBSERVATION_CHARS = 6_000;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public PromptAgentLlmClient(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentAction decide(
            String question,
            AgentContext context,
            List<Map<String, Object>> availableTools) {
        String rawResponse = llmClient.callLlm(
                buildPrompt(question, context, availableTools),
                "agent");
        return parseAction(rawResponse);
    }

    String buildPrompt(
            String question,
            AgentContext context,
            List<Map<String, Object>> availableTools) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(PROTOCOL_MARKER).append('\n');
        prompt.append("You are an action planner. Return one action only. ");
        prompt.append("Do not include hidden reasoning or markdown.\n");
        prompt.append("CURRENT_STEP: ").append(context.getStep()).append('\n');
        prompt.append("CURRENT_QUESTION:\n").append(limit(question, MAX_MESSAGE_CHARS)).append('\n');
        prompt.append("CONVERSATION:\n");

        List<AgentContext.Message> history = context.getHistory();
        if (history.isEmpty()) {
            prompt.append("(none)\n");
        } else {
            for (AgentContext.Message message : history) {
                if (message.isAssistantToolCall()) {
                    prompt.append("assistant tool_call: ")
                            .append(message.toolName())
                            .append(' ')
                            .append(writeJson(message.arguments()))
                            .append('\n');
                    continue;
                }
                prompt.append(message.role())
                        .append(": ")
                        .append(limit(message.content(), MAX_MESSAGE_CHARS))
                        .append('\n');
            }
        }

        prompt.append("LATEST_OBSERVATION:\n")
                .append(latestObservation(context.getToolHistory()))
                .append('\n');
        prompt.append("AVAILABLE_TOOLS:\n")
                .append(writeJson(availableTools))
                .append('\n');
        prompt.append("RESPONSE_FORMAT:\n");
        prompt.append("{\"type\":\"tool_call\",\"toolName\":\"tool_name\",");
        prompt.append("\"arguments\":{\"key\":\"value\"}}\n");
        prompt.append("or\n");
        prompt.append("{\"type\":\"final_answer\",\"answer\":\"answer for the user\"}\n");
        return prompt.toString();
    }

    AgentAction parseAction(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            throw new IllegalStateException("Agent LLM 返回空响应");
        }

        String candidate = extractJson(rawResponse.trim());
        if (candidate == null) {
            // 兼容尚未升级为 Function Calling 的普通文本模型。
            return AgentAction.finalAnswer(rawResponse.trim());
        }

        try {
            JsonNode root = objectMapper.readTree(candidate);
            String type = text(root, "type").toLowerCase(Locale.ROOT);
            if ("tool_call".equals(type)) {
                String toolName = firstText(root, "toolName", "tool_name");
                if (toolName.isBlank()) {
                    throw new IllegalStateException("tool_call 缺少 toolName");
                }
                JsonNode argumentsNode = root.has("arguments")
                        ? root.get("arguments")
                        : root.get("params");
                Map<String, Object> arguments = argumentsNode == null || argumentsNode.isNull()
                        ? Map.of()
                        : objectMapper.convertValue(
                                argumentsNode,
                                new TypeReference<Map<String, Object>>() {});
                return AgentAction.toolCall(
                        firstText(root, "toolCallId", "tool_call_id", "id"),
                        toolName,
                        arguments);
            }
            if ("final_answer".equals(type)) {
                String answer = firstText(root, "answer", "content");
                if (answer.isBlank()) {
                    throw new IllegalStateException("final_answer 缺少 answer");
                }
                return AgentAction.finalAnswer(answer);
            }
            throw new IllegalStateException("不支持的 Agent 动作类型: " + type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Agent LLM 返回的 JSON 无法解析", e);
        }
    }

    private String latestObservation(List<ToolCallRecord> history) {
        if (history.isEmpty()) {
            return "(none)";
        }
        ToolCallRecord record = history.get(history.size() - 1);
        String observation;
        if (record.denied()) {
            observation = record.denyReason();
        } else if (record.result() != null && record.result().success()) {
            observation = record.result().content();
        } else if (record.result() != null) {
            observation = record.result().error();
        } else {
            observation = "工具未返回结果";
        }
        return limit(record.toolName() + ": " + observation, MAX_OBSERVATION_CHARS);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("工具定义序列化失败", e);
        }
    }

    private String extractJson(String response) {
        String normalized = response;
        if (normalized.startsWith("```")) {
            int firstLineEnd = normalized.indexOf('\n');
            int closingFence = normalized.lastIndexOf("```");
            if (firstLineEnd >= 0 && closingFence > firstLineEnd) {
                normalized = normalized.substring(firstLineEnd + 1, closingFence).trim();
            }
        }
        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        return start >= 0 && end > start
                ? normalized.substring(start, end + 1)
                : null;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private String limit(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars
                ? text
                : text.substring(0, maxChars) + "...[truncated]";
    }
}
