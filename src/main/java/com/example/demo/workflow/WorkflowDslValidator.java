package com.example.demo.workflow;

import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WorkflowDslValidator {
    private final int maxNodes;
    public WorkflowDslValidator(@Value("${app.workflow.max-nodes}") int maxNodes) {
        this.maxNodes = Math.max(1, maxNodes);
    }

    public void validate(WorkflowDefinition definition) {
        if (definition == null || definition.code() == null || definition.code().isBlank()) {
            throw new IllegalArgumentException("Workflow code 不能为空");
        }
        if (definition.nodes().isEmpty() || definition.nodes().size() > maxNodes) {
            throw new IllegalArgumentException("Workflow 节点数量不合法");
        }
        Set<String> ids = new HashSet<>();
        for (WorkflowNode node : definition.nodes()) {
            if (node.id() == null || node.id().isBlank() || !ids.add(node.id())) {
                throw new IllegalArgumentException("Workflow 节点 ID 重复或为空");
            }
            if (node.type() == WorkflowNode.Type.TOOL
                    && node.config().get("maxRetries") instanceof Number retries
                    && retries.intValue() > 0
                    && !node.config().containsKey("idempotencyKey")) {
                throw new IllegalArgumentException(
                        "Retrying Tool nodes requires idempotencyKey: " + node.id());
            }
        }
        for (WorkflowNode node : definition.nodes()) {
            for (String dependency : node.dependsOn()) {
                if (!ids.contains(dependency) || dependency.equals(node.id())) {
                    throw new IllegalArgumentException("Workflow 依赖节点不存在: " + dependency);
                }
            }
        }
        // Kahn's algorithm catches cycles before anything is persisted/executed.
        Set<String> completed = new HashSet<>();
        while (completed.size() < ids.size()) {
            int before = completed.size();
            for (WorkflowNode node : definition.nodes()) {
                if (!completed.contains(node.id()) && completed.containsAll(node.dependsOn())) {
                    completed.add(node.id());
                }
            }
            if (completed.size() == before) throw new IllegalArgumentException("Workflow 包含循环依赖");
        }
    }
}
