package com.example.demo.skill;

import com.example.demo.security.UserRole;
import java.util.Arrays;
import org.springframework.stereotype.Service;

@Service
public class SkillPermissionService {
    public boolean canUse(SkillDefinition skill, UserRole role) {
        if (skill == null || !skill.enabled()) return false;
        String value = role == null ? UserRole.GUEST.name() : role.name();
        return Arrays.stream(skill.allowedRoles().split(","))
                .map(String::trim).anyMatch(value::equalsIgnoreCase);
    }
}
