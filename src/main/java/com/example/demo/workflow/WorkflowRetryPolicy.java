package com.example.demo.workflow;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Bounded exponential retry policy for a single idempotent node execution. */
@Component
public class WorkflowRetryPolicy {
    public <T> Outcome<T> execute(WorkflowNode node, Supplier<T> operation) {
        int maxRetries = number(node, "maxRetries", 0, 5);
        long baseBackoffMs = number(node, "retryBackoffMs", 100, 10_000);
        RuntimeException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return new Outcome<>(operation.get(), attempt);
            } catch (RuntimeException ex) {
                last = ex;
                if (attempt == maxRetries) {
                    throw new RetryExhaustedException(attempt, ex);
                }
                long delay = Math.min(10_000L, baseBackoffMs << Math.min(attempt, 10));
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RetryExhaustedException(attempt, interrupted);
                }
            }
        }
        throw new RetryExhaustedException(maxRetries, last);
    }

    private int number(WorkflowNode node, String key, int fallback, int max) {
        Object value = node.config().get(key);
        if (!(value instanceof Number number)) {
            return fallback;
        }
        return Math.max(0, Math.min(max, number.intValue()));
    }

    public record Outcome<T>(T value, int retryCount) {}

    public static class RetryExhaustedException extends RuntimeException {
        private final int retryCount;
        public RetryExhaustedException(int retryCount, Throwable cause) {
            super(cause == null ? "Workflow node failed" : cause.getMessage(), cause);
            this.retryCount = retryCount;
        }
        public int retryCount() { return retryCount; }
    }
}
