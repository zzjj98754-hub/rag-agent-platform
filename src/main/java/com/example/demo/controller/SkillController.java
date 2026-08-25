package com.example.demo.controller;

import com.example.demo.security.CurrentUserProvider;
import com.example.demo.skill.SkillDefinition;
import com.example.demo.skill.SkillPermissionService;
import com.example.demo.skill.SkillRegistry;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/skills")
public class SkillController {
    private final SkillRegistry registry;
    private final SkillPermissionService permissions;
    private final CurrentUserProvider currentUser;

    public SkillController(SkillRegistry registry, SkillPermissionService permissions,
            CurrentUserProvider currentUser) {
        this.registry = registry;
        this.permissions = permissions;
        this.currentUser = currentUser;
    }

    @GetMapping
    public Map<String, Object> list() {
        var role = currentUser.requireCurrentUser().role();
        return Map.of("skills", registry.list().stream()
                .filter(skill -> permissions.canUse(skill, role)).toList());
    }
}
