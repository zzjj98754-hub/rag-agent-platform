package com.example.demo.workflow;

/** Strategy boundary for one workflow node type. */
public interface WorkflowNodeHandler {
    WorkflowNode.Type type();
    Object execute(WorkflowNode node, WorkflowNodeContext context);
}
