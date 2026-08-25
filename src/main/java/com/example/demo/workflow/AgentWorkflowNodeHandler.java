package com.example.demo.workflow;

import com.example.demo.agent.SpringAiToolCallingManager;
import com.example.demo.agent.ToolContextFactory;
import com.example.demo.security.AuthenticatedUser;
import com.example.demo.service.SpringAiLlmClient;
import org.springframework.stereotype.Component;

@Component
public class AgentWorkflowNodeHandler implements WorkflowNodeHandler {
    private final SpringAiLlmClient llm;
    private final SpringAiToolCallingManager tools;
    private final ToolContextFactory contexts;

    public AgentWorkflowNodeHandler(
            SpringAiLlmClient llm,
            SpringAiToolCallingManager tools,
            ToolContextFactory contexts) {
        this.llm = llm;
        this.tools = tools;
        this.contexts = contexts;
    }
    @Override public WorkflowNode.Type type() { return WorkflowNode.Type.AGENT; }
    @Override public Object execute(WorkflowNode node, WorkflowNodeContext context) {
        String prompt = String.valueOf(node.config().getOrDefault(
                "prompt", context.input()));
        AuthenticatedUser user = new AuthenticatedUser(
                context.ownerId(), "workflow", context.role());
        return llm.callAgent(prompt, tools.callbacks(),
                contexts.create(user, context.sessionId()));
    }
}
