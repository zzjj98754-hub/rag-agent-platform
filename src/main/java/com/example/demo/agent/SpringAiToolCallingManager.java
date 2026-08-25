package com.example.demo.agent;

import com.example.demo.agent.tool.ToolRegistry;
import com.example.demo.agent.tool.ToolScheduler;
import com.example.demo.agent.tool.ToolScheduler.ToolCallRecord;
import com.example.demo.security.UserRole;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

/** Stable domain wrapper corresponding to Spring AI's ToolCallingManager. */
@Service
public class SpringAiToolCallingManager {
    private final ToolRegistry registry;
    private final ToolScheduler scheduler;
    private final ObjectMapper mapper;
    private final Map<String, List<ToolCallRecord>> histories = new ConcurrentHashMap<>();
    private final GuardedToolCallingManager manager = new GuardedToolCallingManager();
    public SpringAiToolCallingManager(
            ToolRegistry registry, ToolScheduler scheduler, ObjectMapper mapper) {
        this.registry = registry;
        this.scheduler = scheduler;
        this.mapper = mapper;
    }
    public List<Map<String, Object>> tools() { return registry.listToolsForLLM(); }
    public List<ToolCallback> callbacks() {
        return registry.toolNames().stream()
                .map(registry::get)
                .map(tool -> (ToolCallback) new ToolDefinitionCallbackAdapter(
                        tool, scheduler, mapper, histories))
                .toList();
    }
    public List<ToolCallback> callbacks(java.util.Collection<String> allowedNames) {
        java.util.Set<String> allowed = java.util.Set.copyOf(allowedNames);
        return callbacks().stream()
                .filter(callback -> allowed.contains(
                        callback.getToolDefinition().name()))
                .toList();
    }
    public org.springframework.ai.model.tool.ToolCallingManager manager() { return manager; }
    public ToolCallRecord execute(String name, Map<String, Object> arguments,
            UserRole role, String sessionId) {
        return scheduler.dispatch(name, arguments, role, sessionId, List.of());
    }
}
