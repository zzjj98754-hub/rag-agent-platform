package com.example.demo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.demo.controller.StreamController;
import com.example.demo.observability.RagObservability;
import com.example.demo.observability.RagRequestObservation;
import com.example.demo.security.AuthenticatedSessionService;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

class StreamingChatServiceTest {

    private AuthenticatedSessionService sessionService;
    private RagPromptService promptService;
    private ConversationCompletionService completionService;
    private StreamingLlmClient llmClient;
    private RagObservability observability;
    private RagRequestObservation observation;
    private PreparedRagPrompt prepared;
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        sessionService = mock(AuthenticatedSessionService.class);
        promptService = mock(RagPromptService.class);
        completionService = mock(ConversationCompletionService.class);
        llmClient = mock(StreamingLlmClient.class);
        observability = mock(RagObservability.class);
        observation = new RagRequestObservation("问题");
        prepared = new PreparedRagPrompt(
                "问题",
                "session-1",
                "prompt",
                List.of());
        scheduler = Executors.newSingleThreadScheduledExecutor();

        when(sessionService.resolveOrCreate(null, "流式会话"))
                .thenReturn("session-1");
        when(sessionService.resolveOrCreate("session-1", "流式会话"))
                .thenReturn("session-1");
        when(observability.beginRequest("问题"))
                .thenReturn(observation);
        when(observability.withObservation(eq(observation), any()))
                .thenAnswer(invocation ->
                        ((Supplier<?>) invocation.getArgument(1)).get());
        when(promptService.prepare("问题", "session-1"))
                .thenReturn(prepared);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void shouldKeepTokenOrderAndEmitIdAndRetryFields() throws Exception {
        when(llmClient.streamChat("prompt"))
                .thenReturn(Flux.just("你", "好"));
        SseReplayBuffer replayBuffer = new SseReplayBuffer(
                64, 60_000, java.time.Clock.systemUTC());
        StreamingChatService service = service(
                new SseEmitterFactory(), replayBuffer);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new StreamController(service))
                .build();

        MvcResult started = mockMvc.perform(
                        MockMvcRequestBuilders.get("/chat/stream")
                                .param("query", "问题")
                                .param("sessionId", "session-1"))
                .andReturn();
        MvcResult completed = mockMvc.perform(
                        MockMvcRequestBuilders.asyncDispatch(started))
                .andReturn();
        String body = completed.getResponse().getContentAsString();

        verify(completionService).complete(prepared, "你好");
        assertThat(body).contains("retry:3000", "event:token", "event:done");
        assertThat(body).containsPattern("id:[0-9]+");
        assertThat(replayBuffer.eventsAfter("session-1", "0").stream()
                .filter(event -> "token".equals(event.name()))
                .map(event -> String.valueOf(
                        ((java.util.Map<?, ?>) event.data()).get("content"))))
                .containsExactly("你", "好");
    }

    @Test
    void shouldCloseWithErrorWhenUpstreamFails() {
        when(llmClient.streamChat("prompt"))
                .thenReturn(Flux.error(new IllegalStateException("upstream")));

        service(new SseEmitterFactory()).streamChat("问题", null, null);

        verify(completionService, never()).complete(any(), any());
        verify(observability).markFailure(observation);
        verify(observability).completeRequest(observation);
    }

    @Test
    void disconnectShouldCancelUpstreamSubscription() {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(llmClient.streamChat("prompt"))
                .thenReturn(Flux.<String>never()
                        .doOnCancel(() -> cancelled.set(true)));
        ControllableEmitter emitter = new ControllableEmitter();
        SseEmitterFactory factory = new SseEmitterFactory() {
            @Override
            public SseEmitter create(long timeoutMillis) {
                return emitter;
            }
        };

        service(factory).streamChat("问题", null, null);
        emitter.disconnect(new IOException("client closed"));

        assertThat(cancelled).isTrue();
        verify(observability).markFailure(observation);
        verify(observability).completeRequest(observation);
    }

    private StreamingChatService service(SseEmitterFactory factory) {
        return service(factory, new SseReplayBuffer(
                64, 60_000, java.time.Clock.systemUTC()));
    }

    private StreamingChatService service(
            SseEmitterFactory factory,
            SseReplayBuffer replayBuffer) {
        return new StreamingChatService(
                sessionService,
                promptService,
                completionService,
                llmClient,
                observability,
                Runnable::run,
                Runnable::run,
                scheduler,
                replayBuffer,
                factory,
                30_000,
                15_000,
                3_000,
                64);
    }

    private static final class ControllableEmitter extends SseEmitter {
        private Consumer<Throwable> errorCallback = ignored -> { };

        private ControllableEmitter() {
            super(30_000L);
        }

        @Override
        public void onError(Consumer<Throwable> callback) {
            this.errorCallback = callback;
        }

        private void disconnect(Throwable error) {
            errorCallback.accept(error);
        }
    }
}
