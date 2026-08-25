package com.example.demo.skill;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Conservative router: explicit selection wins; weak implicit matches abstain. */
@Service
public class SkillRouter {
    private final SkillRegistry registry;

    public SkillRouter(SkillRegistry registry) {
        this.registry = registry;
    }

    public Optional<SkillDefinition> route(String explicitCode, String query) {
        if (explicitCode != null && !explicitCode.isBlank()) {
            SkillDefinition selected = registry.get(explicitCode);
            if (selected == null || !selected.enabled()) {
                throw new IllegalArgumentException("Skill is not available: " + explicitCode);
            }
            return Optional.of(selected);
        }
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        return registry.list().stream()
                .filter(SkillDefinition::enabled)
                .map(skill -> new Candidate(skill, score(skill, normalized)))
                .filter(candidate -> candidate.score >= 2)
                .max(Comparator.comparingInt(Candidate::score))
                .map(Candidate::skill);
    }

    private int score(SkillDefinition skill, String query) {
        int score = query.contains(skill.code().toLowerCase(Locale.ROOT)) ? 3 : 0;
        if (skill.name() != null && query.contains(skill.name().toLowerCase(Locale.ROOT))) {
            score += 2;
        }
        return score;
    }

    private record Candidate(SkillDefinition skill, int score) {}
}
