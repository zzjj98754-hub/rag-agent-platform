package com.example.demo.workflow;

import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class ConditionWorkflowNodeHandler implements WorkflowNodeHandler {
    @Override public WorkflowNode.Type type() { return WorkflowNode.Type.CONDITION; }
    @Override public Object execute(WorkflowNode node, WorkflowNodeContext context) {
        Object actual = node.config().containsKey("source")
                ? context.values().get(String.valueOf(node.config().get("source")))
                : context.input();
        Object expected = node.config().get("equals");
        boolean matched = expected == null
                ? Boolean.TRUE.equals(actual)
                : Objects.equals(actual, expected);
        return java.util.Map.of("matched", matched, "value", actual == null ? "" : actual);
    }
}
