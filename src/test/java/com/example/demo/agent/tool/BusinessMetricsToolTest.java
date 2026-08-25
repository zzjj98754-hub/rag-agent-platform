package com.example.demo.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.security.UserRole;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BusinessMetricsToolTest {
    private final BusinessMetricsTool tool = new BusinessMetricsTool();

    @Test
    void shouldExposeOnlyDeterministicDesensitizedFixture() {
        ToolResult result = tool.execute(Map.of("month", "2026-01"));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("desensitized acceptance fixture", "1250000");
        assertThat(tool.execute(Map.of("month", "2025-12")).success()).isFalse();
        assertThat(new ToolPermissionEvaluator().check(tool, UserRole.ANALYST)).isTrue();
        assertThat(new ToolPermissionEvaluator().check(tool, UserRole.USER)).isFalse();
    }
}
