package com.example.demo.agent;

import com.example.demo.agent.tool.ToolScheduler;
import com.example.demo.agent.tool.ToolScheduler.ToolCallRecord;
import com.example.demo.security.UserRole;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

/** Adapts the platform registry to Spring AI while retaining all guard rails. */
public final class ToolDefinitionCallbackAdapter implements ToolCallback {
    private final com.example.demo.agent.tool.ToolDefinition tool;
    private final ToolScheduler scheduler;
    private final ObjectMapper mapper;
    private final org.springframework.ai.tool.definition.ToolDefinition definition;
    private final Map<String, List<ToolCallRecord>> histories;

    public ToolDefinitionCallbackAdapter(
            com.example.demo.agent.tool.ToolDefinition tool,
            ToolScheduler scheduler,
            ObjectMapper mapper,
            Map<String, List<ToolCallRecord>> histories) {
        this.tool = tool;
        this.scheduler = scheduler;
        this.mapper = mapper;
        this.histories = histories == null ? new ConcurrentHashMap<>() : histories;
        try {
            this.definition = org.springframework.ai.tool.definition.ToolDefinition.builder()
                    .name(tool.name())
                    .description(tool.description())
                    .inputSchema(mapper.writeValueAsString(tool.parametersSchema()))
                    .build();
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid tool schema: " + tool.name(), ex);
        }
    }

    @Override
    public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String arguments) {
        return call(arguments, new ToolContext(Map.of()));
    }

    @Override
    public String call(String arguments, ToolContext context) {
        Map<String, Object> values;
        try {
            values = mapper.readValue(
                    arguments == null || arguments.isBlank() ? "{}" : arguments,
                    new TypeReference<>() {});
        } catch (Exception ex) {
            return "Tool arguments are not valid JSON: " + ex.getMessage();
        }
        Map<String, Object> metadata = context == null ? Map.of() : context.getContext();
        String sessionId = String.valueOf(metadata.getOrDefault(
                ToolContextFactory.SESSION_ID, "spring-ai"));
        UserRole role = parseRole(metadata.get(ToolContextFactory.USER_ROLE));
        List<ToolCallRecord> history = histories.getOrDefault(sessionId, List.of());
        ToolCallRecord record = scheduler.dispatch(
                tool.name(), values, role, sessionId, history);
        histories.compute(sessionId, (key, previous) -> {
            java.util.ArrayList<ToolCallRecord> next = new java.util.ArrayList<>(
                    previous == null ? List.of() : previous);
            next.add(record);
            return List.copyOf(next);
        });
        if (record.denied() || record.terminal()) {
            return "Tool call denied: " + record.denyReason();
        }
        if (record.result() == null) {
            return "Tool returned no result";
        }
        return record.result().success()
                ? record.result().content()
                : "Tool failed: " + record.result().error();
    }

    private UserRole parseRole(Object value) {
        try {
            return value == null ? UserRole.GUEST : UserRole.valueOf(value.toString());
        } catch (IllegalArgumentException ex) {
            return UserRole.GUEST;
        }
    }
}
