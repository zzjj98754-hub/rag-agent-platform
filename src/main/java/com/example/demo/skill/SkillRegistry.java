package com.example.demo.skill;

import java.util.List;
import org.springframework.stereotype.Service;

/** DB-backed Skill read model and transactional version registry. */
@Service
public class SkillRegistry {
    private final SkillPersistenceService persistence;

    public SkillRegistry(SkillPersistenceService persistence) {
        this.persistence = persistence;
    }

    public SkillDefinition register(SkillDefinition skill) {
        if (skill == null) {
            throw new IllegalArgumentException("Skill is required");
        }
        requireCode(skill.code());
        return persistence.save(skill);
    }

    public SkillDefinition get(String code) {
        return persistence.find(code);
    }

    public List<SkillDefinition> list() {
        return persistence.list();
    }

    public SkillDefinition enable(String code, boolean enabled) {
        return persistence.enable(code, enabled);
    }

    public SkillVersion publish(SkillVersion version) {
        requireCode(version.skillCode());
        if (version.promptTemplate() == null || version.promptTemplate().isBlank()) {
            throw new IllegalArgumentException("Skill Prompt is required");
        }
        return persistence.publish(version);
    }

    public SkillDefinition rollback(String code, int version) {
        return persistence.rollback(code, version);
    }

    public List<SkillVersion> versions(String code) {
        return persistence.versions(code);
    }

    public SkillVersion resolveVersion(String code, Integer version) {
        SkillDefinition definition = persistence.require(code);
        int selected = version == null ? definition.currentVersion() : version;
        return versions(code).stream()
                .filter(item -> item.version() == selected)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Skill version does not exist: " + code + "@" + selected));
    }

    private void requireCode(String code) {
        if (code == null || !code.matches("[A-Za-z0-9._-]{2,64}")) {
            throw new IllegalArgumentException("Invalid Skill code");
        }
    }
}
