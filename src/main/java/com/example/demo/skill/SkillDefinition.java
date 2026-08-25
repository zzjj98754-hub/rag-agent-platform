package com.example.demo.skill;

import java.util.List;
import java.util.Map;

/** Versioned business capability: prompt template, tools and constraints. */
public record SkillDefinition(
        String code,
        String name,
        String description,
        int currentVersion,
        boolean enabled,
        List<String> toolRefs,
        Map<String, Object> parameterSchema,
        String allowedRoles) {

    public SkillDefinition {
        toolRefs = toolRefs == null ? List.of() : List.copyOf(toolRefs);
        parameterSchema = parameterSchema == null ? Map.of("type", "object") : Map.copyOf(parameterSchema);
        allowedRoles = allowedRoles == null ? "USER,ANALYST,ADMIN" : allowedRoles;
    }
}
