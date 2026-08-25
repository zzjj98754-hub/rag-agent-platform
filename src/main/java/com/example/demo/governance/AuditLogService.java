package com.example.demo.governance;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Durable security and platform audit trail. */
@Service
public class AuditLogService {
    private final JdbcTemplate jdbc;
    public AuditLogService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void record(
            Long userId, String action, String outcome, Long elapsedMs,
            String resourceType, String resourceId, String detail,
            String ip, String traceId) {
        jdbc.update("""
                INSERT INTO audit_log
                    (user_id, action, outcome, elapsed_ms, resource_type,
                     resource_id, detail, ip, trace_id)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?)
                """, userId, action, outcome, elapsedMs, resourceType,
                resourceId, jsonDetail(detail), ip, traceId);
    }

    public void record(Long userId, String action, String resourceType,
            String resourceId, String detail, String traceId) {
        record(userId, action, "SUCCESS", null, resourceType,
                resourceId, detail, null, traceId);
    }

    public List<AuditLogEntity> recent(int limit) {
        int safeLimit = Math.max(1, Math.min(500, limit));
        return jdbc.query("""
                SELECT * FROM audit_log ORDER BY id DESC LIMIT ?
                """, (rs, row) -> new AuditLogEntity(
                rs.getLong("id"), (Long) rs.getObject("user_id"),
                rs.getString("action"), rs.getString("outcome"),
                (Long) rs.getObject("elapsed_ms"), rs.getString("resource_type"),
                rs.getString("resource_id"), rs.getString("detail"),
                rs.getString("ip"), rs.getString("trace_id"),
                rs.getTimestamp("created_at").toInstant()), safeLimit);
    }

    private String jsonDetail(String detail) {
        if (detail == null || detail.isBlank()) return "{}";
        String value = detail.trim();
        if ((value.startsWith("{") && value.endsWith("}"))
                || (value.startsWith("[") && value.endsWith("]"))) {
            return value;
        }
        return "{\"message\":\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"}";
    }
}
