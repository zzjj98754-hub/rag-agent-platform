package com.example.demo.service;

import com.example.demo.observability.RagObservability;
import com.example.demo.observability.RagRequestObservation;
import com.example.demo.observability.RagStage;
import com.example.demo.security.AuthenticatedSessionService;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * 流式聊天用例编排：会话校验、RAG 准备、模型订阅与 SSE 生命周期。
 */
@Service
public class StreamingChatService implements StreamingChatUseCase {

    private static final Logger log =
            LoggerFactory.getLogger(StreamingChatService.class);

    private final AuthenticatedSessionService authenticatedSessionService;
    private final RagPromptService ragPromptService;
    private final ConversationCompletionService completionService;
    private final StreamingLlmClient streamingLlmClient;
    private final RagObservability observability;
    private final Executor ragAsyncExecutor;
    private final Executor sseSendExecutor;
    private final ScheduledExecutorService heartbeatScheduler;
    private final SseReplayBuffer replayBuffer;
    private final SseEmitterFactory emitterFactory;
    private final long emitterTimeoutMillis;
    private final long heartbeatIntervalMillis;
    private final long retryMillis;
    private final int bufferCapacity;
    private final Scheduler sendScheduler;
    private final ConcurrentHashMap<SseEmitter, SendContext> sendContexts =
            new ConcurrentHashMap<>();

    public StreamingChatService(
            AuthenticatedSessionService authenticatedSessionService,
            RagPromptService ragPromptService,
            ConversationCompletionService completionService,
            StreamingLlmClient streamingLlmClient,
            RagObservability observability,
            @Qualifier("ragAsyncExecutor") Executor ragAsyncExecutor,
            @Qualifier("sseSendExecutor") Executor sseSendExecutor,
            ScheduledExecutorService sseHeartbeatScheduler,
            SseReplayBuffer replayBuffer,
            SseEmitterFactory emitterFactory,
            @Value("${app.llm.streaming.emitter-timeout-ms}")
            long emitterTimeoutMillis,
            @Value("${app.llm.streaming.heartbeat-interval-ms}")
            long heartbeatIntervalMillis,
            @Value("${app.llm.streaming.retry-ms}")
            long retryMillis,
            @Value("${app.llm.streaming.buffer-capacity}")
            int bufferCapacity) {
        this.authenticatedSessionService =
                authenticatedSessionService;
        this.ragPromptService = ragPromptService;
        this.completionService = completionService;
        this.streamingLlmClient = streamingLlmClient;
        this.observability = observability;
        this.ragAsyncExecutor = ragAsyncExecutor;
        this.sseSendExecutor = sseSendExecutor;
        this.heartbeatScheduler = sseHeartbeatScheduler;
        this.replayBuffer = replayBuffer;
        this.emitterFactory = emitterFactory;
        this.emitterTimeoutMillis = emitterTimeoutMillis;
        this.heartbeatIntervalMillis = Math.max(1_000, heartbeatIntervalMillis);
        this.retryMillis = Math.max(1_000, retryMillis);
        this.bufferCapacity = Math.max(8, bufferCapacity);
        this.sendScheduler = Schedulers.fromExecutor(sseSendExecutor);
    }

    @Override
    public SseEmitter streamChat(
            String query,
            String requestedSessionId,
            String lastEventId) {
        String sessionId = authenticatedSessionService.resolveOrCreate(
                requestedSessionId,
                "流式会话");
        SseEmitter emitter = emitterFactory.create(emitterTimeoutMillis);
        AtomicBoolean terminated = new AtomicBoolean();
        AtomicReference<Disposable> subscription =
                new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> heartbeat =
                new AtomicReference<>();
        StreamingMetricsContext metrics =
                new StreamingMetricsContext();
        Map<String, String> traceContext =
                MDC.getCopyOfContextMap();
        sendContexts.put(
                emitter,
                new SendContext(sessionId, new Object()));

        registerCallbacks(
                emitter,
                sessionId,
                terminated,
                subscription,
                heartbeat,
                metrics);
        try {
            ragAsyncExecutor.execute(() -> withTrace(
                    traceContext,
                    () -> startStream(
                            query,
                            sessionId,
                            emitter,
                            lastEventId,
                            terminated,
                            subscription,
                            heartbeat,
                            metrics,
                            traceContext)));
        } catch (RuntimeException e) {
            fail(
                    emitter,
                    sessionId,
                    terminated,
                    subscription,
                    heartbeat,
                    e,
                    metrics);
        }
        return emitter;
    }

    private void startStream(
            String query,
            String sessionId,
            SseEmitter emitter,
            String lastEventId,
            AtomicBoolean terminated,
            AtomicReference<Disposable> subscription,
            AtomicReference<ScheduledFuture<?>> heartbeat,
            StreamingMetricsContext metrics,
            Map<String, String> traceContext) {
        if (replayIfRequested(
                emitter,
                sessionId,
                lastEventId,
                terminated)) {
            return;
        }
        startHeartbeat(
                emitter,
                sessionId,
                terminated,
                subscription,
                heartbeat,
                metrics,
                traceContext);
        RagRequestObservation observation =
                metrics.startRequest(query);
        try {
            emit(
                    emitter,
                    "session",
                    Map.of("sessionId", sessionId));
            emit(
                    emitter,
                    "plan",
                    Map.of("steps", List.of(
                            "query_rewrite", "hybrid_retrieval",
                            "relevance_gate", "generation", "citation_validation")));
            emit(
                    emitter,
                    "context",
                    Map.of(
                            "phase", "retrieving",
                            "message", "正在检索相关文档..."));
            PreparedRagPrompt prepared = observability.withObservation(
                    observation,
                    () -> ragPromptService.prepare(query, sessionId));
            emit(
                    emitter,
                    "summary",
                    Map.of("applied", prepared.prompt().contains("历史摘要：")));
            emit(
                    emitter,
                    "citations",
                    Map.of("items", buildCitations(prepared)));
            emit(
                    emitter,
                    "trace",
                    buildTrace(observation, "pending"));
            emit(
                    emitter,
                    "context",
                    Map.of(
                            "phase", "generating",
                            "message", "正在生成回答..."));

            StringBuilder response = new StringBuilder();
            metrics.startLlm();
            Disposable disposable = streamingLlmClient
                    .streamChat(prepared.prompt())
                    .onBackpressureBuffer(
                            bufferCapacity,
                            ignored -> log.warn(
                                    "SSE Token 缓冲区溢出 | session={}",
                                    sessionId),
                            BufferOverflowStrategy.ERROR)
                    .publishOn(sendScheduler)
                    .subscribe(
                            token -> withTrace(
                                    traceContext,
                                    () -> onToken(
                                            emitter,
                                            sessionId,
                                            token,
                                            response,
                                            terminated,
                                            subscription,
                                            heartbeat,
                                            metrics)),
                            error -> withTrace(
                                    traceContext,
                                    () -> fail(
                                            emitter,
                                            sessionId,
                                            terminated,
                                            subscription,
                                            heartbeat,
                                            error,
                                            metrics)),
                            () -> withTrace(
                                    traceContext,
                                    () -> complete(
                                            emitter,
                                            prepared,
                                            response.toString(),
                                            terminated,
                                            heartbeat,
                                            metrics)));
            subscription.set(disposable);
            if (terminated.get()) {
                disposable.dispose();
            }
        } catch (Exception e) {
            fail(
                    emitter,
                    sessionId,
                    terminated,
                    subscription,
                    heartbeat,
                    e,
                    metrics);
        }
    }

    private void onToken(
            SseEmitter emitter,
            String sessionId,
            String token,
            StringBuilder response,
            AtomicBoolean terminated,
            AtomicReference<Disposable> subscription,
            AtomicReference<ScheduledFuture<?>> heartbeat,
            StreamingMetricsContext metrics) {
        if (terminated.get()) {
            return;
        }
        try {
            response.append(token);
            metrics.recordToken(token);
            emit(
                    emitter,
                    "token",
                    Map.of("content", token));
        } catch (IOException e) {
            fail(
                    emitter,
                    sessionId,
                    terminated,
                    subscription,
                    heartbeat,
                    e,
                    metrics);
        }
    }

    private void complete(
            SseEmitter emitter,
            PreparedRagPrompt prepared,
            String response,
            AtomicBoolean terminated,
            AtomicReference<ScheduledFuture<?>> heartbeat,
            StreamingMetricsContext metrics) {
        if (!terminated.compareAndSet(false, true)) {
            return;
        }
        cancelHeartbeat(heartbeat);
        try {
            completionService.complete(prepared, response);
            metrics.finish("success", false);
            emit(
                    emitter,
                    "trace",
                    buildTrace(
                            metrics.observation(),
                            "completed"));
            Map<String, Object> doneData =
                    new LinkedHashMap<>();
            doneData.put(
                    "sessionId",
                    prepared.sessionId());
            doneData.put("length", response.length());
            doneData.put(
                    "timings",
                    buildTimings(metrics.observation()));
            doneData.put(
                    "tokens",
                    metrics.estimatedTokens());
            doneData.put("tokenAccounting", "estimated");
            emit(
                    emitter,
                    "done",
                    doneData);
            emitter.complete();
            log.info(
                    "SSE 流式完成 | session={} response_length={}",
                    prepared.sessionId(),
                    response.length());
        } catch (Exception e) {
            metrics.finish("failure", true);
            emitError(emitter);
            emitter.completeWithError(e);
            log.error(
                    "SSE 完成处理失败 | session={}",
                    prepared.sessionId(),
                    e);
        }
    }

    private void fail(
            SseEmitter emitter,
            String sessionId,
            AtomicBoolean terminated,
            AtomicReference<Disposable> subscription,
            AtomicReference<ScheduledFuture<?>> heartbeat,
            Throwable error,
            StreamingMetricsContext metrics) {
        if (!terminated.compareAndSet(false, true)) {
            return;
        }
        Disposable disposable = subscription.get();
        if (disposable != null) {
            disposable.dispose();
        }
        cancelHeartbeat(heartbeat);
        metrics.finish("failure", true);
        emitError(emitter);
        emitter.completeWithError(error);
        log.error(
                "SSE 流式异常 | session={} message={}",
                sessionId,
                error.getMessage(),
                error);
    }

    private void registerCallbacks(
            SseEmitter emitter,
            String sessionId,
            AtomicBoolean terminated,
            AtomicReference<Disposable> subscription,
            AtomicReference<ScheduledFuture<?>> heartbeat,
            StreamingMetricsContext metrics) {
        emitter.onTimeout(() -> {
            if (terminated.compareAndSet(false, true)) {
                Disposable disposable = subscription.get();
                if (disposable != null) {
                    disposable.dispose();
                }
                cancelHeartbeat(heartbeat);
                metrics.finish("timeout", true);
                log.warn("SSE 连接超时 | session={}", sessionId);
                emitter.complete();
            }
        });
        emitter.onError(error -> {
            if (terminated.compareAndSet(false, true)) {
                Disposable disposable = subscription.get();
                if (disposable != null) {
                    disposable.dispose();
                }
                cancelHeartbeat(heartbeat);
                metrics.finish("disconnect", true);
            }
            sendContexts.remove(emitter);
            log.info(
                    "SSE 客户端断开 | session={} message={}",
                    sessionId,
                    error.getMessage());
        });
        emitter.onCompletion(() -> {
            Disposable disposable = subscription.get();
            if (disposable != null && !disposable.isDisposed()) {
                disposable.dispose();
            }
            cancelHeartbeat(heartbeat);
            sendContexts.remove(emitter);
        });
    }

    private boolean replayIfRequested(
            SseEmitter emitter,
            String sessionId,
            String lastEventId,
            AtomicBoolean terminated) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return false;
        }
        List<SseReplayBuffer.BufferedEvent> events =
                replayBuffer.eventsAfter(sessionId, lastEventId);
        if (events.isEmpty() && !replayBuffer.hasEvents(sessionId)) {
            log.info(
                    "Last-Event-ID 无可重放状态，启动新流 | session={} lastEventId={}",
                    sessionId,
                    lastEventId);
            return false;
        }
        try {
            for (SseReplayBuffer.BufferedEvent event : events) {
                sendBuffered(emitter, event);
            }
            String latestEvent = events.isEmpty()
                    ? replayBuffer.latestEventName(sessionId)
                    : events.get(events.size() - 1).name();
            boolean terminal = "done".equals(latestEvent)
                    || "error".equals(latestEvent);
            if (!terminal) {
                emit(
                        emitter,
                        "reconnect",
                        Map.of(
                                "resumable", false,
                                "message", "已重放可用事件；上游生成已取消，请重新发送问题"));
            }
            terminated.set(true);
            emitter.complete();
            log.info(
                    "SSE 重放完成 | session={} lastEventId={} replayed={} terminal={}",
                    sessionId,
                    lastEventId,
                    events.size(),
                    terminal);
            return true;
        } catch (IOException e) {
            terminated.set(true);
            emitter.completeWithError(e);
            sendContexts.remove(emitter);
            return true;
        }
    }

    private void startHeartbeat(
            SseEmitter emitter,
            String sessionId,
            AtomicBoolean terminated,
            AtomicReference<Disposable> subscription,
            AtomicReference<ScheduledFuture<?>> heartbeat,
            StreamingMetricsContext metrics,
            Map<String, String> traceContext) {
        ScheduledFuture<?> task = heartbeatScheduler.scheduleAtFixedRate(
                () -> {
                    if (terminated.get()) {
                        return;
                    }
                    sseSendExecutor.execute(() -> withTrace(
                            traceContext,
                            () -> {
                                if (terminated.get()) {
                                    return;
                                }
                                try {
                                    emit(
                                            emitter,
                                            "heartbeat",
                                            Map.of(
                                                    "sessionId", sessionId,
                                                    "timestamp", System.currentTimeMillis()));
                                } catch (IOException e) {
                                    fail(
                                            emitter,
                                            sessionId,
                                            terminated,
                                            subscription,
                                            heartbeat,
                                            e,
                                            metrics);
                                }
                            }));
                },
                heartbeatIntervalMillis,
                heartbeatIntervalMillis,
                TimeUnit.MILLISECONDS);
        heartbeat.set(task);
        if (terminated.get()) {
            task.cancel(false);
        }
    }

    private void cancelHeartbeat(
            AtomicReference<ScheduledFuture<?>> heartbeat) {
        ScheduledFuture<?> task = heartbeat.getAndSet(null);
        if (task != null) {
            task.cancel(false);
        }
    }

    private final class StreamingMetricsContext {

        private final AtomicReference<RagRequestObservation> observation =
                new AtomicReference<>();
        private final AtomicLong llmStartedAt = new AtomicLong();
        private final AtomicLong characterCount = new AtomicLong();
        private final AtomicBoolean finished = new AtomicBoolean();

        private RagRequestObservation startRequest(String query) {
            RagRequestObservation started =
                    observability.beginRequest(query);
            observation.set(started);
            return started;
        }

        private void startLlm() {
            llmStartedAt.set(System.nanoTime());
        }

        private void recordToken(String token) {
            characterCount.addAndGet(
                    token.codePointCount(0, token.length()));
        }

        private void finish(String outcome, boolean failed) {
            RagRequestObservation currentObservation =
                    observation.get();
            if (currentObservation == null
                    || !finished.compareAndSet(false, true)) {
                return;
            }
            long startedAt = llmStartedAt.get();
            if (startedAt > 0) {
                long characters = characterCount.get();
                long estimatedTokens = characters == 0
                        ? 0
                        : Math.max(1, (characters + 1) / 2);
                observability.recordLlm(
                        currentObservation,
                        System.nanoTime() - startedAt,
                        estimatedTokens,
                        outcome);
            }
            if (failed) {
                observability.markFailure(currentObservation);
            }
            observability.completeRequest(currentObservation);
        }

        private RagRequestObservation observation() {
            return observation.get();
        }

        private long estimatedTokens() {
            long characters = characterCount.get();
            return characters == 0
                    ? 0
                    : Math.max(1, (characters + 1) / 2);
        }
    }

    private void emit(
            SseEmitter emitter,
            String event,
            Object data) throws IOException {
        SendContext context = sendContexts.get(emitter);
        if (context == null) {
            throw new IOException("SSE 连接上下文已释放");
        }
        synchronized (context.monitor()) {
            String id = replayBuffer.nextId(context.sessionId());
            if (!"heartbeat".equals(event)) {
                replayBuffer.append(
                        context.sessionId(),
                        id,
                        event,
                        data);
            }
            emitter.send(SseEmitter.event()
                    .id(id)
                    .name(event)
                    .reconnectTime(retryMillis)
                    .data(data, MediaType.APPLICATION_JSON));
        }
    }

    private void sendBuffered(
            SseEmitter emitter,
            SseReplayBuffer.BufferedEvent event) throws IOException {
        SendContext context = sendContexts.get(emitter);
        if (context == null) {
            throw new IOException("SSE 连接上下文已释放");
        }
        synchronized (context.monitor()) {
            emitter.send(SseEmitter.event()
                    .id(event.id())
                    .name(event.name())
                    .reconnectTime(retryMillis)
                    .data(event.data(), MediaType.APPLICATION_JSON));
        }
    }

    private List<Map<String, Object>> buildCitations(
            PreparedRagPrompt prepared) {
        return prepared.documents().stream()
                .map(document -> {
                    Map<String, Object> citation =
                            new LinkedHashMap<>();
                    citation.put(
                            "title",
                            sourceTitle(document.id()));
                    citation.put("chunkId", document.id());
                    citation.put("score", document.score());
                    citation.put(
                            "scoreType",
                            document.scoreType().name());
                    citation.put(
                            "preview",
                            preview(document.effectiveText()));
                    return citation;
                })
                .toList();
    }

    private Map<String, Object> buildTrace(
            RagRequestObservation observation,
            String generationStatus) {
        List<Map<String, Object>> steps = List.of(
                traceStep(
                        "query_rewrite",
                        "Query Rewrite",
                        "completed",
                        0),
                traceStep(
                        "bm25",
                        "BM25 Recall",
                        "completed",
                        duration(observation, RagStage.BM25)),
                traceStep(
                        "vector",
                        "Vector Recall",
                        "completed",
                        duration(observation, RagStage.EMBEDDING)),
                traceStep(
                        "rrf",
                        "RRF Fusion",
                        "completed",
                        0),
                traceStep(
                        "rerank",
                        "BGE Rerank",
                        "completed",
                        duration(observation, RagStage.RERANK)),
                traceStep(
                        "generate",
                        "Generate",
                        generationStatus,
                        duration(observation, RagStage.LLM)));
        return Map.of(
                "steps", steps,
                "timings", buildTimings(observation));
    }

    private Map<String, Object> traceStep(
            String key,
            String label,
            String status,
            long durationMs) {
        return Map.of(
                "key", key,
                "label", label,
                "status", status,
                "durationMs", durationMs);
    }

    private Map<String, Long> buildTimings(
            RagRequestObservation observation) {
        Map<String, Long> timings = new LinkedHashMap<>();
        timings.put(
                "retrievalMs",
                duration(observation, RagStage.RETRIEVAL));
        timings.put(
                "bm25Ms",
                duration(observation, RagStage.BM25));
        timings.put(
                "embeddingMs",
                duration(observation, RagStage.EMBEDDING));
        timings.put(
                "rerankMs",
                duration(observation, RagStage.RERANK));
        timings.put(
                "llmMs",
                duration(observation, RagStage.LLM));
        timings.put(
                "totalMs",
                duration(observation, RagStage.TOTAL));
        return timings;
    }

    private long duration(
            RagRequestObservation observation,
            RagStage stage) {
        return observation == null
                ? 0
                : observation.durationMillis(stage);
    }

    private String sourceTitle(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) {
            return "知识库文档";
        }
        int separator = chunkId.indexOf(':');
        return separator > 0
                ? chunkId.substring(0, separator)
                : chunkId;
    }

    private String preview(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized =
                text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 180
                ? normalized
                : normalized.substring(0, 180) + "...";
    }

    private void emitError(SseEmitter emitter) {
        try {
            emit(
                    emitter,
                    "error",
                    Map.of("message", "流式生成失败，请稍后重试"));
        } catch (IOException ignored) {
            log.debug("SSE 客户端已断开，无法发送错误事件");
        }
    }

    private void withTrace(
            Map<String, String> traceContext,
            Runnable action) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            if (traceContext == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(traceContext);
            }
            action.run();
        } finally {
            if (previous == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        }
    }

    private record SendContext(String sessionId, Object monitor) {
    }
}
