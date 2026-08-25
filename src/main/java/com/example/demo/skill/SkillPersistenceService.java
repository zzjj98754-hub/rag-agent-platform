package com.example.demo.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transactional MySQL repository for versioned Skills. */
@Service
public class SkillPersistenceService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SkillPersistenceService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public SkillDefinition save(SkillDefinition skill) {
        jdbc.update("""
                INSERT INTO skill
                    (code, name, description, parameter_schema, current_version,
                     publish_status, enabled, allowed_roles, update_time)
                VALUES (?, ?, ?, CAST(? AS JSON), ?, ?, ?, ?, NOW(3))
                ON DUPLICATE KEY UPDATE name=VALUES(name),
                    description=VALUES(description),
                    parameter_schema=VALUES(parameter_schema),
                    enabled=VALUES(enabled), allowed_roles=VALUES(allowed_roles),
                    update_time=NOW(3)
                """,
                skill.code(), skill.name(), skill.description(), write(skill.parameterSchema()),
                Math.max(0, skill.currentVersion()),
                skill.currentVersion() > 0 ? "PUBLISHED" : "DRAFT",
                skill.enabled(), skill.allowedRoles());
        return require(skill.code());
    }

    public SkillDefinition find(String code) {
        List<SkillDefinition> values = jdbc.query(
                "SELECT * FROM skill WHERE code=?", this::mapSkill, code);
        return values.isEmpty() ? null : values.get(0);
    }

    public SkillDefinition require(String code) {
        SkillDefinition value = find(code);
        if (value == null) {
            throw new IllegalArgumentException("Skill does not exist: " + code);
        }
        return value;
    }

    public List<SkillDefinition> list() {
        return jdbc.query("SELECT * FROM skill ORDER BY code", this::mapSkill);
    }

    @Transactional
    public SkillDefinition enable(String code, boolean enabled) {
        int updated = jdbc.update(
                "UPDATE skill SET enabled=?, update_time=NOW(3) WHERE code=?",
                enabled, code);
        if (updated == 0) {
            throw new IllegalArgumentException("Skill does not exist: " + code);
        }
        return require(code);
    }

    @Transactional
    public SkillVersion publish(SkillVersion requested) {
        SkillDefinition definition = require(requested.skillCode());
        Integer next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(sv.version), 0) + 1
                FROM skill_version sv JOIN skill s ON s.id=sv.skill_id
                WHERE s.code=? FOR UPDATE
                """, Integer.class, requested.skillCode());
        int version = next == null ? 1 : next;
        Long skillId = jdbc.queryForObject(
                "SELECT id FROM skill WHERE code=?", Long.class, requested.skillCode());
        jdbc.update("""
                INSERT INTO skill_version
                    (skill_id, version, prompt_template, parameter_schema,
                     tool_refs, change_log, status)
                VALUES (?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), ?, 'PUBLISHED')
                """,
                skillId, version, requested.promptTemplate(),
                write(definition.parameterSchema()), write(requested.toolRefs()),
                requested.changeLog());
        jdbc.update("""
                UPDATE skill SET current_version=?, publish_status='PUBLISHED',
                    update_time=NOW(3) WHERE id=?
                """, version, skillId);
        return new SkillVersion(
                requested.skillCode(), version, requested.promptTemplate(),
                requested.toolRefs(), requested.changeLog(), requested.createdBy());
    }

    @Transactional
    public SkillDefinition rollback(String code, int version) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM skill_version sv
                JOIN skill s ON s.id=sv.skill_id
                WHERE s.code=? AND sv.version=?
                """, Long.class, code, version);
        if (count == null || count == 0) {
            throw new IllegalArgumentException("Skill version does not exist: " + code + "@" + version);
        }
        jdbc.update("UPDATE skill SET current_version=?, update_time=NOW(3) WHERE code=?",
                version, code);
        return require(code);
    }

    public List<SkillVersion> versions(String code) {
        return jdbc.query("""
                SELECT s.code, sv.version, sv.prompt_template, sv.tool_refs,
                       sv.change_log, COALESCE(CAST(sv.created_by AS CHAR), '') created_by
                FROM skill_version sv JOIN skill s ON s.id=sv.skill_id
                WHERE s.code=? ORDER BY sv.version
                """, (rs, row) -> new SkillVersion(
                rs.getString("code"), rs.getInt("version"),
                rs.getString("prompt_template"),
                readList(rs.getString("tool_refs")),
                rs.getString("change_log"), rs.getString("created_by")), code);
    }

    private SkillDefinition mapSkill(java.sql.ResultSet rs, int row)
            throws java.sql.SQLException {
        return new SkillDefinition(
                rs.getString("code"), rs.getString("name"),
                rs.getString("description"), rs.getInt("current_version"),
                rs.getBoolean("enabled"), List.of(),
                readMap(rs.getString("parameter_schema")),
                rs.getString("allowed_roles"));
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid Skill JSON", ex);
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            return json == null ? Map.of("type", "object")
                    : mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid persisted Skill schema", ex);
        }
    }

    private List<String> readList(String json) {
        try {
            return json == null ? List.of()
                    : mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid persisted Skill tools", ex);
        }
    }
}
