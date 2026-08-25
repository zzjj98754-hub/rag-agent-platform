package com.example.demo.workflow;

import com.example.demo.service.LlmClient;
import org.springframework.stereotype.Component;

@Component
public class LlmWorkflowNodeHandler implements WorkflowNodeHandler {
    private final LlmClient llm;
    public LlmWorkflowNodeHandler(LlmClient llm) { this.llm = llm; }
    @Override public WorkflowNode.Type type() { return WorkflowNode.Type.LLM; }
    @Override public Object execute(WorkflowNode node, WorkflowNodeContext context) {
        String prompt = String.valueOf(node.config().getOrDefault(
                "prompt", context.input()));
        return llm.callLlm(prompt, String.valueOf(
                node.config().getOrDefault("model", "default")));
    }
}
