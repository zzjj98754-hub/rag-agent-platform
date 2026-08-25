package com.example.demo.skill;

import com.example.demo.agent.tool.ToolRegistry;
import com.example.demo.agent.tool.ToolScheduler;
import com.example.demo.agent.tool.ToolScheduler.ToolCallRecord;
import com.example.demo.security.UserRole;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class SkillRuntime {

    private final SkillRegistry registry;
    private final SkillPermissionService permissions;
    private final ToolRegistry tools;
    private final ToolScheduler scheduler;
    private final SkillPromptRenderer renderer;

    public SkillRuntime(SkillRegistry registry, SkillPermissionService permissions,
            ToolRegistry tools, ToolScheduler scheduler, SkillPromptRenderer renderer) {
        this.registry = registry;
        this.permissions = permissions;
        this.tools = tools;
        this.scheduler = scheduler;
        this.renderer = renderer;
    }

    public SkillExecution prepare(String code, Integer version, Map<String, Object> variables,
            UserRole role) {
        SkillDefinition definition = registry.get(code);
        if (!permissions.canUse(definition, role)) {
            throw new SecurityException("无权使用 Skill: " + code);
        }
        SkillVersion selected = registry.resolveVersion(code, version);
        String prompt = renderer.render(
                selected.promptTemplate(), variables, definition.parameterSchema());
        List<String> allowedTools = selected.toolRefs().isEmpty()
                ? definition.toolRefs() : selected.toolRefs();
        for (String tool : allowedTools) {
            if (!tools.contains(tool)) throw new IllegalArgumentException("Skill 引用的工具不存在: " + tool);
        }
        return new SkillExecution(definition, selected, prompt, allowedTools);
    }

    public ToolCallRecord call(SkillExecution execution, String tool, Map<String, Object> params,
            UserRole role, String sessionId) {
        if (!execution.toolRefs().contains(tool)) {
            throw new SecurityException("工具不在 Skill 允许范围内: " + tool);
        }
        return scheduler.dispatch(tool, params, role, sessionId, List.of());
    }

    public record SkillExecution(SkillDefinition definition, SkillVersion version,
            String prompt, List<String> toolRefs) {
        public SkillExecution {
            toolRefs = List.copyOf(toolRefs);
        }
    }
}
