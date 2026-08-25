package com.example.demo.workflow;

import java.util.List;
import java.util.Map;

public record WorkflowNode(
        String id,
        Type type,
        List<String> dependsOn,
        Map<String, Object> config) {
    public enum Type { LLM, AGENT, SKILL, TOOL, CONDITION, PARALLEL }
    public WorkflowNode {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        config = config == null ? Map.of() : Map.copyOf(config);
    }
}
