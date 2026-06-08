package com.example.demo.agent.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Tool 调度器 —— 统一入口，负责权限校验 + 超时控制 + 死循环拦截 + 步数限制。
 *
 * 面试要点：
 * - 单步超时用 Future.get(timeout)，防止一个工具卡死整个 Agent 循环
 * - 死循环检测：连续 N 次调用同一工具 + 同一参数 → 判定为死循环，强制终止
 * - 最大步数限制：防止 LLM 反复调用工具占用资源
 * - 调度器是 Agent 的"安全边界"，所有工具调用必须经过此处
 */
@Component
public class ToolScheduler {

    private static final Logger log = LoggerFactory.getLogger(ToolScheduler.class);

    @Autowired
    private ToolRegistry registry;

    @Autowired
    private ToolPermissionEvaluator permissionEvaluator;

    @Value("${app.agent.max-steps:10}")
    private int maxSteps;

    @Value("${app.agent.step-timeout-ms:30000}")
    private long stepTimeoutMs;

    @Value("${app.agent.dead-loop-threshold:3}")
    private int deadLoopThreshold;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 执行工具调用 —— 带完整安全防护。
     *
     * @param toolName   工具名称
     * @param params     参数
     * @param role       调用者角色
     * @param sessionId  会话 ID（用于死循环检测上下文）
     * @param stepHistory 本轮已执行的工具调用历史
     */
    public ToolCallRecord dispatch(String toolName, Map<String, Object> params,
                                    String role, String sessionId,
                                    List<ToolCallRecord> stepHistory) {

        // 1. 步数限制
        if (stepHistory.size() >= maxSteps) {
            log.warn("已达最大步数限制 | session={} steps={}", sessionId, stepHistory.size());
            return ToolCallRecord.denied(toolName, "已达最大推理步数(" + maxSteps + ")，终止循环");
        }

        // 2. 死循环检测
        String loopReason = detectDeadLoop(toolName, params, stepHistory);
        if (loopReason != null) {
            log.warn("检测到死循环 | session={} tool={} reason={}", sessionId, toolName, loopReason);
            return ToolCallRecord.denied(toolName, loopReason);
        }

        // 3. 工具是否存在
        ToolDefinition tool = registry.get(toolName);
        if (tool == null) {
            log.warn("工具不存在 | tool={}", toolName);
            return ToolCallRecord.denied(toolName, "工具 '" + toolName + "' 不存在");
        }

        // 4. 权限校验
        if (!permissionEvaluator.check(tool, role)) {
            log.warn("工具权限拒绝 | tool={} role={}", toolName, role);
            return ToolCallRecord.denied(toolName, "无权限调用工具 '" + toolName + "'，需要权限: " + tool.requiredPermissions());
        }

        // 5. 执行（带超时）
        log.info("执行工具 | tool={} session={} role={}", toolName, sessionId, role);
        long start = System.currentTimeMillis();

        try {
            ToolResult result = executeWithTimeout(tool, params);
            long elapsed = System.currentTimeMillis() - start;
            log.info("工具执行完成 | tool={} success={} elapsed={}ms", toolName, result.success(), elapsed);
            return ToolCallRecord.success(toolName, params, result);
        } catch (TimeoutException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("工具执行超时 | tool={} timeout={}ms elapsed={}ms", toolName, stepTimeoutMs, elapsed);
            return ToolCallRecord.timeout(toolName, "工具执行超时(" + stepTimeoutMs + "ms)");
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("工具执行异常 | tool={} error={}", toolName, e.getMessage());
            return ToolCallRecord.error(toolName, e.getMessage());
        }
    }

    /**
     * 带超时的工具执行。
     */
    private ToolResult executeWithTimeout(ToolDefinition tool, Map<String, Object> params)
            throws TimeoutException, InterruptedException, ExecutionException {
        Future<ToolResult> future = executor.submit(() -> tool.execute(params));
        try {
            return future.get(stepTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    /**
     * 死循环检测：连续 deadLoopThreshold 次调用同一工具 + 相同参数则判定为死循环。
     */
    private String detectDeadLoop(String toolName, Map<String, Object> params,
                                   List<ToolCallRecord> history) {
        if (history.size() < deadLoopThreshold) {
            return null;
        }

        String paramsStr = params.toString();
        int consecutive = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            ToolCallRecord record = history.get(i);
            if (record.toolName().equals(toolName)
                    && record.params().toString().equals(paramsStr)) {
                consecutive++;
                if (consecutive >= deadLoopThreshold) {
                    return "检测到死循环：连续 " + consecutive
                            + " 次调用 '" + toolName + "' 且参数相同";
                }
            } else {
                break; // 不连续则停止
            }
        }
        return null;
    }

    /** 工具调用记录 */
    public record ToolCallRecord(
            String toolName,
            Map<String, Object> params,
            ToolResult result,
            boolean denied,
            String denyReason
    ) {
        public static ToolCallRecord success(String toolName, Map<String, Object> params, ToolResult result) {
            return new ToolCallRecord(toolName, params, result, false, null);
        }

        public static ToolCallRecord denied(String toolName, String reason) {
            return new ToolCallRecord(toolName, null, null, true, reason);
        }

        public static ToolCallRecord timeout(String toolName, String reason) {
            return new ToolCallRecord(toolName, null, null, true, reason);
        }

        public static ToolCallRecord error(String toolName, String reason) {
            return new ToolCallRecord(toolName, null, null, true, reason);
        }
    }

}
