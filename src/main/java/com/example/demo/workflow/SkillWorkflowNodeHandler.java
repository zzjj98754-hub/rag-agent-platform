package com.example.demo.workflow;

import com.example.demo.agent.SpringAiToolCallingManager;
import com.example.demo.agent.ToolContextFactory;
import com.example.demo.security.AuthenticatedUser;
import com.example.demo.service.SpringAiLlmClient;
import com.example.demo.skill.SkillRuntime;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SkillWorkflowNodeHandler implements WorkflowNodeHandler {
    private final SkillRuntime skills;
    private final SpringAiLlmClient llm;
    private final SpringAiToolCallingManager tools;
    private final ToolContextFactory contexts;
    public SkillWorkflowNodeHandler(
            SkillRuntime skills, SpringAiLlmClient llm,
            SpringAiToolCallingManager tools, ToolContextFactory contexts) {
        this.skills = skills; this.llm = llm; this.tools = tools; this.contexts = contexts;
    }
    @Override public WorkflowNode.Type type() { return WorkflowNode.Type.SKILL; }
    @Override public Object execute(WorkflowNode node, WorkflowNodeContext context) {
        var execution = skills.prepare(
                String.valueOf(node.config().get("skill")),
                node.config().get("version") instanceof Number number
                        ? number.intValue() : null,
                asMap(context.input()), context.role());
        AuthenticatedUser user = new AuthenticatedUser(
                context.ownerId(), "workflow", context.role());
        return llm.callAgent(
                execution.prompt(), tools.callbacks(execution.toolRefs()),
                contexts.create(user, context.sessionId()));
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
