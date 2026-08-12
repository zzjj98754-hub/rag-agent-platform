package com.example.demo.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.security.UserRole;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolPermissionEvaluatorTest {

    private final ToolPermissionEvaluator evaluator =
            new ToolPermissionEvaluator();

    @Test
    void shouldApplyRolePermissionMatrix() {
        ToolDefinition calculator = tool("calculator", "CALCULATOR");
        ToolDefinition knowledge = tool("search_knowledge", "KNOWLEDGE_SEARCH");

        assertThat(evaluator.check(calculator, UserRole.ADMIN)).isTrue();
        assertThat(evaluator.check(knowledge, UserRole.ADMIN)).isTrue();
        assertThat(evaluator.check(calculator, UserRole.USER)).isTrue();
        assertThat(evaluator.check(knowledge, UserRole.USER)).isTrue();
        assertThat(evaluator.check(calculator, UserRole.GUEST)).isFalse();
        assertThat(evaluator.check(knowledge, UserRole.GUEST)).isTrue();
    }

    private ToolDefinition tool(String name, String permission) {
        return new ToolDefinition() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "test";
            }

            @Override
            public Map<String, Object> parametersSchema() {
                return Map.of();
            }

            @Override
            public ToolResult execute(Map<String, Object> params) {
                return ToolResult.ok(name, "ok", 1);
            }

            @Override
            public Set<String> requiredPermissions() {
                return Set.of(permission);
            }
        };
    }
}
