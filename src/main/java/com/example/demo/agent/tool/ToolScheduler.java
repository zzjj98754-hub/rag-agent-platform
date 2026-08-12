package com.example.demo.agent.tool;

import com.example.demo.security.UserRole;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 工具调度安全边界：步骤/循环检测 -> Registry 查找 -> 权限校验 -> ToolExecutor 执行。
 */
@Component
public class ToolScheduler {

    private static final Logger log = LoggerFactory.getLogger(ToolScheduler.class);

    private final ToolRegistry registry;
    private final ToolPermissionEvaluator permissionEvaluator;
    private final ToolExecutor toolExecutor;
    private final int maxSteps;
    private final int deadLoopThreshold;

    public ToolScheduler(
            ToolRegistry registry,
            ToolPermissionEvaluator permissionEvaluator,
            ToolExecutor toolExecutor,
            @Value("${app.agent.max-steps}") int maxSteps,
            @Value("${app.agent.dead-loop-threshold}") int deadLoopThreshold) {
        this.registry = registry;
        this.permissionEvaluator = permissionEvaluator;
        this.toolExecutor = toolExecutor;
        this.maxSteps = Math.max(1, maxSteps);
        this.deadLoopThreshold = Math.max(1, deadLoopThreshold);
    }

    public ToolCallRecord dispatch(
            String toolName,
            Map<String, Object> params,
            UserRole role,
            String sessionId,
            List<ToolCallRecord> stepHistory) {
        return dispatch(
                null,
                toolName,
                params,
                role,
                sessionId,
                stepHistory);
    }

    public ToolCallRecord dispatch(
            String toolCallId,
            String toolName,
            Map<String, Object> params,
            UserRole role,
            String sessionId,
            List<ToolCallRecord> stepHistory) {
        Map<String, Object> safeParams = immutableCopy(params);
        List<ToolCallRecord> safeHistory = stepHistory == null ? List.of() : stepHistory;

        if (safeHistory.size() >= maxSteps) {
            String reason = "已达到最大工具调用步数(" + maxSteps + ")，终止循环";
            log.warn("已达最大步骤限制 | session={} steps={}", sessionId, safeHistory.size());
            return ToolCallRecord.terminal(
                    toolCallId,
                    toolName,
                    safeParams,
                    reason,
                    StopReason.MAX_STEPS);
        }

        String loopReason = detectDeadLoop(toolName, safeParams, safeHistory);
        if (loopReason != null) {
            log.warn(
                    "检测到重复调用循环 | session={} tool={} reason={}",
                    sessionId,
                    toolName,
                    loopReason);
            return ToolCallRecord.terminal(
                    toolCallId,
                    toolName,
                    safeParams,
                    loopReason,
                    StopReason.LOOP_DETECTED);
        }

        ToolDefinition tool = registry.get(toolName);
        if (tool == null) {
            return ToolCallRecord.denied(
                    toolCallId,
                    toolName,
                    safeParams,
                    "工具 '" + toolName + "' 不存在");
        }
        if (!permissionEvaluator.check(tool, role)) {
            return ToolCallRecord.denied(
                    toolCallId,
                    toolName,
                    safeParams,
                    "无权限调用工具 '" + toolName + "'，需要权限: "
                            + tool.requiredPermissions());
        }

        log.info(
                "执行工具 | toolCallId={} tool={} session={} role={}",
                toolCallId,
                toolName,
                sessionId,
                role);
        ToolResult result = toolExecutor.execute(tool, safeParams);
        return result.success()
                ? ToolCallRecord.success(toolCallId, toolName, safeParams, result)
                : ToolCallRecord.error(toolCallId, toolName, safeParams, result);
    }

    private String detectDeadLoop(
            String toolName,
            Map<String, Object> params,
            List<ToolCallRecord> history) {
        if (history.size() < deadLoopThreshold) {
            return null;
        }

        int consecutive = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            ToolCallRecord record = history.get(i);
            if (Objects.equals(record.toolName(), toolName)
                    && Objects.equals(record.params(), params)) {
                consecutive++;
                if (consecutive >= deadLoopThreshold) {
                    return "检测到重复调用循环：工具 '" + toolName
                            + "' 及相同参数已连续执行 " + consecutive + " 次";
                }
            } else {
                break;
            }
        }
        return null;
    }

    public enum StopReason {
        NONE,
        MAX_STEPS,
        LOOP_DETECTED
    }

    public record ToolCallRecord(
            String toolCallId,
            String toolName,
            Map<String, Object> params,
            ToolResult result,
            boolean denied,
            String denyReason,
            boolean terminal,
            StopReason stopReason) {

        public ToolCallRecord {
            params = immutableCopy(params);
            stopReason = stopReason == null ? StopReason.NONE : stopReason;
        }

        public static ToolCallRecord success(
                String toolName,
                Map<String, Object> params,
                ToolResult result) {
            return success(null, toolName, params, result);
        }

        public static ToolCallRecord success(
                String toolCallId,
                String toolName,
                Map<String, Object> params,
                ToolResult result) {
            return new ToolCallRecord(
                    toolCallId,
                    toolName,
                    params,
                    result,
                    false,
                    null,
                    false,
                    StopReason.NONE);
        }

        public static ToolCallRecord denied(
                String toolName,
                Map<String, Object> params,
                String reason) {
            return denied(null, toolName, params, reason);
        }

        public static ToolCallRecord denied(
                String toolCallId,
                String toolName,
                Map<String, Object> params,
                String reason) {
            return new ToolCallRecord(
                    toolCallId,
                    toolName,
                    params,
                    null,
                    true,
                    reason,
                    false,
                    StopReason.NONE);
        }

        public static ToolCallRecord error(
                String toolName,
                Map<String, Object> params,
                ToolResult result) {
            return error(null, toolName, params, result);
        }

        public static ToolCallRecord error(
                String toolCallId,
                String toolName,
                Map<String, Object> params,
                ToolResult result) {
            return new ToolCallRecord(
                    toolCallId,
                    toolName,
                    params,
                    result,
                    false,
                    null,
                    false,
                    StopReason.NONE);
        }

        public static ToolCallRecord terminal(
                String toolName,
                Map<String, Object> params,
                String reason,
                StopReason stopReason) {
            return terminal(null, toolName, params, reason, stopReason);
        }

        public static ToolCallRecord terminal(
                String toolCallId,
                String toolName,
                Map<String, Object> params,
                String reason,
                StopReason stopReason) {
            return new ToolCallRecord(
                    toolCallId,
                    toolName,
                    params,
                    null,
                    true,
                    reason,
                    true,
                    stopReason);
        }
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source) {
        return source == null || source.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
