package com.example.demo.agent.tool;

import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Read-only, deterministic data fixture for pre-production acceptance only. */
@Component
public class BusinessMetricsTool implements ToolDefinition {
    @Override public String name() { return "business_metrics"; }

    @Override public String description() {
        return "Read the desensitized fixed monthly business metrics used only for platform acceptance.";
    }

    @Override public Map<String, Object> parametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "month", Map.of("type", "string", "description", "Acceptance fixture month, e.g. 2026-01")));
    }

    @Override public Set<String> requiredPermissions() { return Set.of("BUSINESS_METRICS"); }

    @Override public ToolResult execute(Map<String, Object> params) {
        long started = System.currentTimeMillis();
        String month = String.valueOf(params == null ? "2026-01" : params.getOrDefault("month", "2026-01"));
        if (!"2026-01".equals(month)) {
            return ToolResult.fail(name(), "Only desensitized acceptance data for 2026-01 is available",
                    System.currentTimeMillis() - started);
        }
        return ToolResult.ok(name(), "{" +
                "\"month\":\"2026-01\",\"revenue\":1250000," +
                "\"targetRevenue\":1200000,\"orders\":3200," +
                "\"refundRate\":0.018,\"newCustomers\":486," +
                "\"note\":\"desensitized acceptance fixture; not production data\"}",
                System.currentTimeMillis() - started);
    }
}
