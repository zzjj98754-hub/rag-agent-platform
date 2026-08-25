package com.example.demo.workflow;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ParallelWorkflowNodeHandler implements WorkflowNodeHandler {
    @Override public WorkflowNode.Type type() { return WorkflowNode.Type.PARALLEL; }
    @Override public Object execute(WorkflowNode node, WorkflowNodeContext context) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        for (String dependency : node.dependsOn()) {
            outputs.put(dependency, context.values().get(dependency));
        }
        return Map.copyOf(outputs);
    }
}
