package com.example.demo.governance;

import java.time.Instant;

public record AuditLogEntity(
        Long id,
        Long userId,
        String action,
        String outcome,
        Long elapsedMs,
        String resourceType,
        String resourceId,
        String detail,
        String ip,
        String traceId,
        Instant createdAt) {}
