package com.example.demo.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.agent.tool.ToolScheduler.StopReason;
import com.example.demo.agent.tool.ToolScheduler.ToolCallRecord;
import com.example.demo.security.UserRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolSchedulerTest {

    private ExecutorService executor;
    private AtomicInteger executions;
    private ToolScheduler scheduler;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        executions = new AtomicInteger();
        ToolDefinition tool = new ToolDefinition() {
            @Override
            public String name() {
                return "repeatable";
            }

            @Override
            public String description() {
                return "test tool";
            }

            @Override
            public Map<String, Object> parametersSchema() {
                return Map.of();
            }

            @Override
            public ToolResult execute(Map<String, Object> params) {
                executions.incrementAndGet();
                return ToolResult.ok(name(), "ok", 1);
            }

            @Override
            public Set<String> requiredPermissions() {
                return Set.of();
            }
        };
        ToolSchemaConverter schemaConverter = new ToolSchemaConverter();
        ToolExecutor toolExecutor = new ToolExecutor(executor, 1_000);
        scheduler = new ToolScheduler(
                new ToolRegistry(List.of(tool), schemaConverter),
                new ToolPermissionEvaluator(),
                toolExecutor,
                5,
                3);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void shouldStopFourthConsecutiveIdenticalCall() {
        List<ToolCallRecord> history = new ArrayList<>();
        Map<String, Object> arguments = Map.of("value", 1);

        for (int i = 0; i < 3; i++) {
            ToolCallRecord record = scheduler.dispatch(
                    "repeatable",
                    arguments,
                    UserRole.USER,
                    "session-1",
                    history);
            assertThat(record.terminal()).isFalse();
            history.add(record);
        }

        ToolCallRecord blocked = scheduler.dispatch(
                "repeatable",
                arguments,
                UserRole.USER,
                "session-1",
                history);

        assertThat(blocked.terminal()).isTrue();
        assertThat(blocked.stopReason()).isEqualTo(StopReason.LOOP_DETECTED);
        assertThat(blocked.denyReason()).contains("已连续执行 3 次");
        assertThat(executions).hasValue(3);
    }

    @Test
    void shouldEnforceMaximumToolSteps() {
        List<ToolCallRecord> history = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            history.add(ToolCallRecord.success(
                    "repeatable",
                    Map.of("value", i),
                    ToolResult.ok("repeatable", "ok", 1)));
        }

        ToolCallRecord blocked = scheduler.dispatch(
                "repeatable",
                Map.of("value", 6),
                UserRole.USER,
                "session-1",
                history);

        assertThat(blocked.terminal()).isTrue();
        assertThat(blocked.stopReason()).isEqualTo(StopReason.MAX_STEPS);
        assertThat(executions).hasValue(0);
    }
}
