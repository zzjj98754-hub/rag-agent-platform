package com.example.demo.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable definition, instance and step repository with optimistic updates. */
@Service
public class WorkflowPersistenceService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WorkflowPersistenceService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public WorkflowDefinition saveDefinition(
            WorkflowDefinition definition, Long ownerId) {
        jdbc.update("""
                INSERT INTO workflow_definition
                    (code, version, dsl, enabled, owner_id, update_time)
                VALUES (?, ?, CAST(? AS JSON), ?, ?, NOW(3))
                ON DUPLICATE KEY UPDATE dsl=VALUES(dsl), enabled=VALUES(enabled),
                    owner_id=VALUES(owner_id), update_time=NOW(3)
                """, definition.code(), definition.version(), write(definition),
                definition.enabled(), ownerId);
        return definition;
    }

    public WorkflowDefinition getDefinition(String code) {
        List<WorkflowDefinition> values = jdbc.query("""
                SELECT dsl FROM workflow_definition
                WHERE code=? AND enabled=TRUE ORDER BY version DESC LIMIT 1
                """, (rs, row) -> read(rs.getString("dsl"), WorkflowDefinition.class), code);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Workflow does not exist: " + code);
        }
        return values.get(0);
    }

    public List<WorkflowDefinition> listDefinitions() {
        return jdbc.query("""
                SELECT wd.dsl FROM workflow_definition wd
                JOIN (SELECT code, MAX(version) version FROM workflow_definition GROUP BY code) latest
                  ON latest.code=wd.code AND latest.version=wd.version
                ORDER BY wd.code
                """, (rs, row) -> read(rs.getString("dsl"), WorkflowDefinition.class));
    }

    @Transactional
    public void createRun(WorkflowRun run, long ownerId, String sessionId) {
        Map<String, Object> definition = jdbc.queryForMap("""
                SELECT id, version FROM workflow_definition
                WHERE code=? ORDER BY version DESC LIMIT 1
                """, run.workflowCode());
        long definitionId = ((Number) definition.get("id")).longValue();
        int definitionVersion = ((Number) definition.get("version")).intValue();
        jdbc.update("""
                INSERT INTO workflow_instance
                    (instance_id, definition_id, version, status, lock_version,
                     current_node, input, output, triggered_by, owner_id,
                     session_id, error, create_time, update_time)
                VALUES (?, ?, ?, ?, 0, ?, CAST(? AS JSON), CAST(? AS JSON),
                        ?, ?, ?, ?, NOW(3), NOW(3))
                """, run.id(), definitionId, definitionVersion,
                run.status().name(), run.currentNode(),
                write(run.input()), write(run.output()), ownerId, ownerId,
                sessionId, run.error());
    }

    @Transactional
    public void updateRun(WorkflowRun run) {
        Long version = jdbc.queryForObject(
                "SELECT lock_version FROM workflow_instance WHERE instance_id=? FOR UPDATE",
                Long.class, run.id());
        int updated = jdbc.update("""
                UPDATE workflow_instance SET status=?, current_node=?,
                    output=CAST(? AS JSON), error=?, lock_version=lock_version+1,
                    update_time=NOW(3)
                WHERE instance_id=? AND lock_version=?
                """, run.status().name(), run.currentNode(), write(run.output()),
                run.error(), run.id(), version);
        if (updated != 1) {
            throw new IllegalStateException("Workflow run was updated concurrently: " + run.id());
        }
    }

    public void saveStep(String runId, WorkflowRun.Step step) {
        String key = runId + ':' + step.nodeId() + ':' + step.retryCount();
        jdbc.update("""
                INSERT INTO workflow_step_execution
                    (instance_id, node_id, execution_key, node_type, input, output,
                     status, error, retry_count, max_retries, started_time, finished_time)
                VALUES (?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), ?, ?, ?, ?,
                        NOW(3), IF(? IN ('SUCCEEDED','FAILED','CANCELLED'), NOW(3), NULL))
                ON DUPLICATE KEY UPDATE output=VALUES(output), status=VALUES(status),
                    error=VALUES(error), retry_count=VALUES(retry_count),
                    finished_time=VALUES(finished_time)
                """, runId, step.nodeId(), key, step.nodeType().name(),
                write(step.input()), write(step.output()), step.status().name(),
                step.error(), step.retryCount(), step.retryCount(), step.status().name());
    }

    public WorkflowRun getRun(String id) {
        List<WorkflowRun> values = jdbc.query("""
                SELECT wi.*, wd.code workflow_code
                FROM workflow_instance wi
                JOIN workflow_definition wd ON wd.id=wi.definition_id
                WHERE wi.instance_id=?
                """, (rs, row) -> new WorkflowRun(
                rs.getString("instance_id"), rs.getString("workflow_code"),
                WorkflowRun.Status.valueOf(rs.getString("status")),
                rs.getString("current_node"),
                readMap(rs.getString("input")), readMap(rs.getString("output")),
                steps(id),
                rs.getTimestamp("create_time").toInstant(),
                terminal(rs.getString("status"))
                        ? rs.getTimestamp("update_time").toInstant() : null,
                rs.getString("error")), id);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Workflow run does not exist: " + id);
        }
        return values.get(0);
    }

    public long ownerId(String id) {
        Long value = jdbc.queryForObject(
                "SELECT owner_id FROM workflow_instance WHERE instance_id=?",
                Long.class, id);
        return value == null ? -1 : value;
    }

    public String sessionId(String id) {
        return jdbc.queryForObject(
                "SELECT session_id FROM workflow_instance WHERE instance_id=?",
                String.class, id);
    }

    public List<String> recoverableRunIds() {
        return jdbc.queryForList("""
                SELECT instance_id FROM workflow_instance
                WHERE status IN ('RUNNING','RETRY_WAIT')
                  AND (next_retry_at IS NULL OR next_retry_at <= NOW(3))
                """, String.class);
    }

    private List<WorkflowRun.Step> steps(String id) {
        return jdbc.query("""
                SELECT * FROM workflow_step_execution
                WHERE instance_id=? ORDER BY id
                """, (rs, row) -> new WorkflowRun.Step(
                rs.getString("node_id"),
                WorkflowNode.Type.valueOf(rs.getString("node_type")),
                WorkflowRun.Status.valueOf(rs.getString("status")),
                readAny(rs.getString("input")), readAny(rs.getString("output")),
                rs.getInt("retry_count"), rs.getString("error")), id);
    }

    private boolean terminal(String status) {
        return List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status);
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid Workflow JSON", ex);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid persisted Workflow", ex);
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            return json == null ? Map.of()
                    : mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid persisted Workflow map", ex);
        }
    }

    private Object readAny(String json) {
        try {
            return json == null ? null : mapper.readValue(json, Object.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid persisted Workflow value", ex);
        }
    }
}
