package com.example.demo.agent;

import com.example.demo.agent.tool.ToolScheduler.ToolCallRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单次 Agent 执行的可变上下文。
 *
 * <p>该对象按请求创建，不作为 Spring 单例共享，因此同一服务实例上的并发请求不会互相污染状态。
 */
public final class AgentContext {

    private final String sessionId;
    private final List<Message> history;
    private final List<ToolCallRecord> toolHistory;
    private int step;

    public AgentContext(String sessionId, List<Message> history) {
        this.sessionId = sessionId;
        this.history = new ArrayList<>(history == null ? List.of() : history);
        this.toolHistory = new ArrayList<>();
    }

    public String getSessionId() {
        return sessionId;
    }

    public List<Message> getHistory() {
        return List.copyOf(history);
    }

    public List<ToolCallRecord> getToolHistory() {
        return List.copyOf(toolHistory);
    }

    public int getStep() {
        return step;
    }

    public int nextStep() {
        return ++step;
    }

    public void addMessage(String role, String content) {
        history.add(new Message(role, content));
    }

    public void addAssistantToolCall(
            String toolCallId,
            String toolName,
            Map<String, Object> arguments) {
        history.add(Message.assistantToolCall(toolCallId, toolName, arguments));
    }

    public void addToolObservation(
            String toolCallId,
            String toolName,
            String observation) {
        history.add(Message.toolObservation(toolCallId, toolName, observation));
    }

    public void addToolCall(ToolCallRecord record) {
        toolHistory.add(record);
    }

    public record Message(
            String role,
            String content,
            String toolCallId,
            String toolName,
            Map<String, Object> arguments) {

        public Message {
            arguments = immutableCopy(arguments);
        }

        public Message(String role, String content) {
            this(role, content, null, null, Map.of());
        }

        public static Message assistantToolCall(
                String toolCallId,
                String toolName,
                Map<String, Object> arguments) {
            return new Message(
                    "assistant",
                    null,
                    toolCallId,
                    toolName,
                    arguments);
        }

        public static Message toolObservation(
                String toolCallId,
                String toolName,
                String observation) {
            return new Message(
                    "tool",
                    observation,
                    toolCallId,
                    toolName,
                    Map.of());
        }

        public boolean isAssistantToolCall() {
            return "assistant".equals(role)
                    && toolCallId != null
                    && toolName != null;
        }

        public boolean isToolObservation() {
            return "tool".equals(role)
                    && toolCallId != null;
        }

        private static Map<String, Object> immutableCopy(Map<String, Object> source) {
            return source == null || source.isEmpty()
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(source));
        }
    }
}
