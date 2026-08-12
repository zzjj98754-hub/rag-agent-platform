package com.example.demo.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 每一轮只允许返回一种可执行动作，不保存或暴露模型内部思维链。
 */
public record AgentAction(
        Type type,
        String toolCallId,
        String toolName,
        Map<String, Object> arguments,
        String answer) {

    public AgentAction {
        arguments = immutableCopy(arguments);
    }

    public static AgentAction toolCall(String toolName, Map<String, Object> arguments) {
        return toolCall(null, toolName, arguments);
    }

    public static AgentAction toolCall(
            String toolCallId,
            String toolName,
            Map<String, Object> arguments) {
        return new AgentAction(
                Type.TOOL_CALL,
                toolCallId,
                toolName,
                arguments,
                null);
    }

    public static AgentAction finalAnswer(String answer) {
        return new AgentAction(
                Type.FINAL_ANSWER,
                null,
                null,
                Map.of(),
                answer);
    }

    public enum Type {
        TOOL_CALL,
        FINAL_ANSWER
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source) {
        return source == null || source.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
