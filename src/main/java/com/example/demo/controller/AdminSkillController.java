package com.example.demo.controller;

import com.example.demo.skill.SkillDefinition;
import com.example.demo.skill.SkillRegistry;
import com.example.demo.skill.SkillVersion;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/skills")
public class AdminSkillController {
    private final SkillRegistry registry;
    public AdminSkillController(SkillRegistry registry) { this.registry = registry; }

    @PostMapping
    public SkillDefinition create(@RequestBody SkillDefinition definition) { return registry.register(definition); }

    @PutMapping("/{code}")
    public SkillDefinition update(@PathVariable String code, @RequestBody SkillDefinition definition) {
        if (!code.equals(definition.code())) throw new IllegalArgumentException("Skill code 不一致");
        return registry.register(definition);
    }

    @PostMapping("/{code}/versions")
    public SkillVersion publish(@PathVariable String code, @RequestBody SkillVersion version) {
        if (!code.equals(version.skillCode())) throw new IllegalArgumentException("Skill code 不一致");
        return registry.publish(version);
    }

    @PostMapping("/{code}/enable")
    public SkillDefinition enable(@PathVariable String code) { return registry.enable(code, true); }

    @PostMapping("/{code}/disable")
    public SkillDefinition disable(@PathVariable String code) { return registry.enable(code, false); }

    @PostMapping("/{code}/rollback/{version}")
    public SkillDefinition rollback(
            @PathVariable String code, @PathVariable int version) {
        return registry.rollback(code, version);
    }

    @GetMapping("/{code}/versions")
    public Map<String, Object> versions(@PathVariable String code) {
        return Map.of("code", code, "versions", registry.versions(code));
    }
}
