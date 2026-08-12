package com.example.demo.agent.tool;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 工具执行器：只负责在线程隔离和超时边界内执行已经完成鉴权的工具。
 */
@Component
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private final ExecutorService executor;
    private final long globalTimeoutMs;

    public ToolExecutor(
            @Qualifier("agentToolExecutor") ExecutorService executor,
            @Value("${app.agent.step-timeout-ms}") long globalTimeoutMs) {
        this.executor = executor;
        this.globalTimeoutMs = Math.max(1, globalTimeoutMs);
    }

    public ToolResult execute(ToolDefinition tool, Map<String, Object> params) {
        long start = System.currentTimeMillis();
        long timeoutMs = globalTimeoutMs;
        Future<ToolResult> future;
        try {
            future = executor.submit(() -> tool.execute(params));
        } catch (RejectedExecutionException e) {
            log.error("工具线程池已饱和 | tool={}", tool.name());
            return ToolResult.fail(tool.name(), "工具执行资源繁忙，请稍后重试", elapsed(start));
        }

        try {
            ToolResult result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return result == null
                    ? ToolResult.fail(tool.name(), "工具未返回结果", elapsed(start))
                    : result;
        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("工具执行超时 | tool={} timeout={}ms", tool.name(), timeoutMs);
            return ToolResult.fail(
                    tool.name(),
                    "工具执行超时(" + timeoutMs + "ms)",
                    elapsed(start));
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return ToolResult.fail(tool.name(), "工具执行被中断", elapsed(start));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.error("工具执行异常 | tool={} error={}", tool.name(), cause.getMessage());
            return ToolResult.fail(tool.name(), cause.getMessage(), elapsed(start));
        }
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
