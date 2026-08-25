package com.example.demo.service;

import com.example.demo.agent.AgentExecutionListener;
import com.example.demo.agent.AgentExecutor;
import com.example.demo.agent.AgentResult;
import com.example.demo.agent.tool.ToolScheduler.ToolCallRecord;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Streams Agent lifecycle events without altering the established RAG SSE endpoint. */
@Service
public class AgentStreamingService {
    private final AgentExecutor agentExecutor;
    private final Executor executor;

    public AgentStreamingService(
            AgentExecutor agentExecutor,
            @Qualifier("ragAsyncExecutor") Executor executor) {
        this.agentExecutor = agentExecutor;
        this.executor = executor;
    }

    public SseEmitter stream(String query, String sessionId) {
        SseEmitter emitter = new SseEmitter(120_000L);
        emitter.onTimeout(emitter::complete);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        executor.execute(() -> {
            long startedAt = System.nanoTime();
            SecurityContext previous = SecurityContextHolder.getContext();
            SecurityContext taskContext = SecurityContextHolder.createEmptyContext();
            taskContext.setAuthentication(authentication);
            SecurityContextHolder.setContext(taskContext);
            try {
                send(emitter, "plan", Map.of("steps", java.util.List.of(
                        "agent_decision", "tool_call", "final_answer")));
                AgentResult result = agentExecutor.execute(query, sessionId, new McpSseListener(emitter));
                send(emitter, "answer", Map.of("content", result.answer()));
                send(emitter, "done", Map.of(
                        "sessionId", result.sessionId(),
                        "status", result.status().name(),
                        "steps", result.steps(),
                        "totalElapsedMs", (System.nanoTime() - startedAt) / 1_000_000L,
                        "completedAt", Instant.now().toString()));
                emitter.complete();
            } catch (Exception ex) {
                try {
                    send(emitter, "error", Map.of("message", "Agent 流式执行失败"));
                } catch (IOException ignored) { }
                emitter.completeWithError(ex);
            } finally {
                SecurityContextHolder.setContext(previous);
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, String name, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
    }

    private final class McpSseListener implements AgentExecutionListener {
        private final SseEmitter emitter;

        private McpSseListener(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void onToolStart(String toolCallId, String toolName, Map<String, Object> arguments) {
            emit(toolCallId, toolName, "STARTED", 0L, null);
        }

        @Override
        public void onToolComplete(ToolCallRecord record) {
            long elapsed = record.result() == null ? 0L : record.result().elapsedMs();
            String status = record.denied() ? "FAILED"
                    : record.result() != null && record.result().success() ? "SUCCEEDED" : "FAILED";
            String error = record.denied() ? record.denyReason()
                    : record.result() == null ? null : record.result().error();
            emit(record.toolCallId(), record.toolName(), status, elapsed, error);
        }

        private void emit(String callId, String name, String status, long elapsedMs, String error) {
            int split = name == null ? -1 : name.indexOf('.');
            if (split <= 0 || split == name.length() - 1) return;
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("toolCallId", callId);
            event.put("server", name.substring(0, split));
            event.put("tool", name.substring(split + 1));
            event.put("status", status);
            event.put("elapsedMs", elapsedMs);
            if (error != null && !error.isBlank()) event.put("error", safeError(error));
            try {
                send(emitter, "mcp_tool", event);
            } catch (IOException ignored) { }
        }

        private String safeError(String value) {
            String normalized = value.replaceAll("(?i)bearer\\s+[^\\s,;]+", "Bearer [REDACTED]");
            return normalized.length() <= 256 ? normalized : normalized.substring(0, 256) + "...";
        }
    }
}
