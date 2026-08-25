package com.example.demo.workflow;

import com.example.demo.agent.tool.ToolScheduler;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ToolWorkflowNodeHandler implements WorkflowNodeHandler {
    private final ToolScheduler scheduler;
    public ToolWorkflowNodeHandler(ToolScheduler scheduler) { this.scheduler = scheduler; }
    @Override public WorkflowNode.Type type() { return WorkflowNode.Type.TOOL; }
    @Override public Object execute(WorkflowNode node, WorkflowNodeContext context) {
        var result = scheduler.dispatch(
                String.valueOf(node.config().get("tool")),
                asMap(node.config().getOrDefault("params", context.input())),
                context.role(), context.sessionId(), List.of());
        if (result.denied() || result.terminal()) {
            throw new SecurityException(result.denyReason());
        }
        if (result.result() == null || !result.result().success()) {
            throw new IllegalStateException(result.result() == null
                    ? "Tool returned no result" : result.result().error());
        }
        return result.result().content();
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
