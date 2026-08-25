package com.example.demo.skill;

import java.util.List;

public record SkillVersion(
        String skillCode,
        int version,
        String promptTemplate,
        List<String> toolRefs,
        String changeLog,
        String createdBy) {
    public SkillVersion {
        toolRefs = toolRefs == null ? List.of() : List.copyOf(toolRefs);
    }
}
