package com.example.demo.workflow;

import java.util.List;

public record WorkflowDefinition(
        String code,
        int version,
        boolean enabled,
        List<WorkflowNode> nodes) {
    public WorkflowDefinition {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }
}
