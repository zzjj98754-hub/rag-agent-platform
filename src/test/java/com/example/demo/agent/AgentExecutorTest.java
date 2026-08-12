package com.example.demo.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.demo.agent.tool.ToolRegistry;
import com.example.demo.agent.tool.ToolResult;
import com.example.demo.agent.tool.ToolScheduler;
import com.example.demo.agent.tool.ToolScheduler.StopReason;
import com.example.demo.agent.tool.ToolScheduler.ToolCallRecord;
import com.example.demo.security.AuthenticatedSessionService;
import com.example.demo.security.AuthenticatedUser;
import com.example.demo.security.CurrentUserProvider;
import com.example.demo.security.UserRole;
import com.example.demo.service.ChatSessionService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class AgentExecutorTest {

    private AgentLlmClient llmClient;
    private ToolRegistry toolRegistry;
    private ToolScheduler toolScheduler;
    private ChatSessionService chatSessionService;
    private CurrentUserProvider currentUserProvider;
    private AuthenticatedSessionService authenticatedSessionService;
    private AgentExecutor executor;

    @BeforeEach
    void setUp() {
        llmClient = mock(AgentLlmClient.class);
        toolRegistry = mock(ToolRegistry.class);
        toolScheduler = mock(ToolScheduler.class);
        chatSessionService = mock(ChatSessionService.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        authenticatedSessionService = mock(AuthenticatedSessionService.class);
        executor = new AgentExecutor(
                llmClient,
                toolRegistry,
                toolScheduler,
                chatSessionService,
                currentUserProvider,
                authenticatedSessionService,
                5);

        AuthenticatedUser currentUser =
                new AuthenticatedUser(7L, "alice", UserRole.USER);
        when(currentUserProvider.requireCurrentUser()).thenReturn(currentUser);
        when(authenticatedSessionService.resolveOrCreate(
                "session-1",
                "Agent 会话",
                currentUser))
                .thenReturn("session-1");
        when(chatSessionService.getHistory("session-1")).thenReturn(List.of());
        when(toolRegistry.listToolsForLLM()).thenReturn(List.of(
                Map.of("name", "calculator")));
    }

    @Test
    void shouldFeedToolObservationBackToLlmAndReturnFinalAnswer() {
        AtomicInteger decisions = new AtomicInteger();
        when(llmClient.decide(eq("计算 2+3"), org.mockito.ArgumentMatchers.any(), anyList()))
                .thenAnswer(invocation -> {
                    AgentContext context = invocation.getArgument(1);
                    if (decisions.getAndIncrement() == 0) {
                        assertThat(context.getToolHistory()).isEmpty();
                        return AgentAction.toolCall(
                                "call-1",
                                "calculator",
                                Map.of("expression", "2+3"));
                    }
                    assertThat(context.getToolHistory()).hasSize(1);
                    assertThat(context.getToolHistory().get(0).toolCallId())
                            .isEqualTo("call-1");
                    assertThat(context.getHistory().get(context.getHistory().size() - 1).content())
                            .contains("2+3 = 5.0");
                    return AgentAction.finalAnswer("结果是 5。");
                });

        ToolCallRecord toolRecord = ToolCallRecord.success(
                "call-1",
                "calculator",
                Map.of("expression", "2+3"),
                ToolResult.ok("calculator", "2+3 = 5.0", 1));
        when(toolScheduler.dispatch(
                eq("call-1"),
                eq("calculator"),
                eq(Map.of("expression", "2+3")),
                eq(UserRole.USER),
                eq("session-1"),
                anyList()))
                .thenReturn(toolRecord);

        AgentResult result = executor.execute("计算 2+3", "session-1");

        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        assertThat(result.answer()).isEqualTo("结果是 5。");
        assertThat(result.steps()).isEqualTo(2);
        assertThat(result.toolHistory()).hasSize(1);
        assertThat(result.toolHistory().get(0).toolCallId()).isEqualTo("call-1");
        assertThat(result.toolHistory().get(0).observation()).contains("2+3 = 5.0");

        InOrder persistenceOrder = inOrder(chatSessionService);
        persistenceOrder.verify(chatSessionService)
                .appendMessage("session-1", "user", "计算 2+3");
        persistenceOrder.verify(chatSessionService)
                .appendMessage(
                        eq("session-1"),
                        eq("tool"),
                        org.mockito.ArgumentMatchers.contains("2+3 = 5.0"));
        persistenceOrder.verify(chatSessionService)
                .appendMessage("session-1", "assistant", "结果是 5。");
    }

    @Test
    void shouldStopAfterConfiguredMaximumSteps() {
        AtomicInteger sequence = new AtomicInteger();
        when(llmClient.decide(eq("持续调用"), org.mockito.ArgumentMatchers.any(), anyList()))
                .thenAnswer(invocation -> {
                    int current = sequence.incrementAndGet();
                    return AgentAction.toolCall(
                            "call-" + current,
                            "calculator",
                            Map.of("expression", current + "+1"));
                });
        when(toolScheduler.dispatch(
                org.mockito.ArgumentMatchers.anyString(),
                eq("calculator"),
                org.mockito.ArgumentMatchers.anyMap(),
                eq(UserRole.USER),
                eq("session-1"),
                anyList()))
                .thenAnswer(invocation -> {
                    String toolCallId = invocation.getArgument(0);
                    Map<String, Object> arguments = invocation.getArgument(2);
                    return ToolCallRecord.success(
                            toolCallId,
                            "calculator",
                            arguments,
                            ToolResult.ok("calculator", "ok", 1));
                });

        AgentResult result = executor.execute("持续调用", "session-1");

        assertThat(result.status()).isEqualTo(AgentResult.Status.MAX_STEPS);
        assertThat(result.steps()).isEqualTo(5);
        assertThat(result.toolHistory()).hasSize(5);
        verify(chatSessionService).appendMessage(
                eq("session-1"),
                eq("assistant"),
                org.mockito.ArgumentMatchers.contains("最大执行步数 5"));
    }

    @Test
    void shouldReturnLoopDetectedWhenSchedulerRejectsRepeatedCall() {
        Map<String, Object> arguments = Map.of("expression", "1+1");
        when(llmClient.decide(eq("重复调用"), org.mockito.ArgumentMatchers.any(), anyList()))
                .thenReturn(AgentAction.toolCall("call-repeat", "calculator", arguments));
        when(toolScheduler.dispatch(
                eq("call-repeat"),
                eq("calculator"),
                eq(arguments),
                eq(UserRole.USER),
                eq("session-1"),
                anyList()))
                .thenAnswer(invocation -> {
                    List<ToolCallRecord> history = invocation.getArgument(5);
                    if (history.size() >= 3) {
                        return ToolCallRecord.terminal(
                                "call-repeat",
                                "calculator",
                                arguments,
                                "检测到重复调用循环",
                                StopReason.LOOP_DETECTED);
                    }
                    return ToolCallRecord.success(
                            "call-repeat",
                            "calculator",
                            arguments,
                            ToolResult.ok("calculator", "2.0", 1));
                });

        AgentResult result = executor.execute("重复调用", "session-1");

        assertThat(result.status()).isEqualTo(AgentResult.Status.LOOP_DETECTED);
        assertThat(result.steps()).isEqualTo(4);
        assertThat(result.toolHistory()).hasSize(4);
        assertThat(result.answer()).contains("重复调用循环");
    }
}
