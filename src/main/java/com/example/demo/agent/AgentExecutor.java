package com.example.demo.agent;

import com.example.demo.agent.AgentResult.Status;
import com.example.demo.agent.AgentResult.ToolTrace;
import com.example.demo.agent.tool.ToolRegistry;
import com.example.demo.agent.tool.ToolOutputSanitizer;
import com.example.demo.agent.tool.ToolScheduler;
import com.example.demo.agent.tool.ToolScheduler.StopReason;
import com.example.demo.agent.tool.ToolScheduler.ToolCallRecord;
import com.example.demo.security.AuthenticatedSessionService;
import com.example.demo.security.AuthenticatedUser;
import com.example.demo.security.CurrentUserProvider;
import com.example.demo.service.ChatSessionService;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

/**
 * 真正的 Agent 推理执行循环：决策 -> 工具 -> Observation -> 再决策 -> 最终回答。
 */
public class AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutor.class);

    private final AgentLlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final ToolScheduler toolScheduler;
    private final ChatSessionService chatSessionService;
    private final CurrentUserProvider currentUserProvider;
    private final AuthenticatedSessionService authenticatedSessionService;
    private final ToolOutputSanitizer outputSanitizer;
    private final int maxSteps;

    public AgentExecutor(
            AgentLlmClient llmClient,
            ToolRegistry toolRegistry,
            ToolScheduler toolScheduler,
            ChatSessionService chatSessionService,
            CurrentUserProvider currentUserProvider,
            AuthenticatedSessionService authenticatedSessionService,
            ToolOutputSanitizer outputSanitizer,
            @Value("${app.agent.max-steps}") int maxSteps) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.toolScheduler = toolScheduler;
        this.chatSessionService = chatSessionService;
        this.currentUserProvider = currentUserProvider;
        this.authenticatedSessionService = authenticatedSessionService;
        this.outputSanitizer = outputSanitizer;
        this.maxSteps = Math.max(1, maxSteps);
    }

    AgentExecutor(
            AgentLlmClient llmClient,
            ToolRegistry toolRegistry,
            ToolScheduler toolScheduler,
            ChatSessionService chatSessionService,
            CurrentUserProvider currentUserProvider,
            AuthenticatedSessionService authenticatedSessionService,
            int maxSteps) {
        this(
                llmClient,
                toolRegistry,
                toolScheduler,
                chatSessionService,
                currentUserProvider,
                authenticatedSessionService,
                new ToolOutputSanitizer(12_000),
                maxSteps);
    }

    public AgentResult execute(String query, String requestedSessionId) {
        String question = requireQuery(query);
        AuthenticatedUser currentUser = currentUserProvider.requireCurrentUser();
        String sessionId = authenticatedSessionService.resolveOrCreate(
                requestedSessionId,
                "Agent 会话",
                currentUser);
        AgentContext context = createContext(sessionId);

        context.addMessage("user", question);
        chatSessionService.appendMessage(sessionId, "user", question);

        try {
            while (context.getStep() < maxSteps) {
                context.nextStep();
                AgentAction action = llmClient.decide(
                        question,
                        context,
                        toolRegistry.listToolsForLLM());

                if (action.type() == AgentAction.Type.FINAL_ANSWER) {
                    return finish(context, action.answer(), Status.COMPLETED);
                }

                String toolCallId = normalizeToolCallId(action.toolCallId());
                context.addAssistantToolCall(
                        toolCallId,
                        action.toolName(),
                        action.arguments());
                ToolCallRecord record = toolScheduler.dispatch(
                        toolCallId,
                        action.toolName(),
                        action.arguments(),
                        currentUser.role(),
                        sessionId,
                        context.getToolHistory());
                context.addToolCall(record);

                if (record.terminal()) {
                    Status status = record.stopReason() == StopReason.LOOP_DETECTED
                            ? Status.LOOP_DETECTED
                            : Status.MAX_STEPS;
                    return finish(context, record.denyReason(), status);
                }

                String observation = toObservation(record);
                context.addToolObservation(
                        toolCallId,
                        action.toolName(),
                        observation);
                chatSessionService.appendMessage(sessionId, "tool", observation);
            }
            return finish(
                    context,
                    "Agent 已达到最大执行步数 " + maxSteps + "，为避免无限循环已停止。",
                    Status.MAX_STEPS);
        } catch (RuntimeException e) {
            log.error("Agent 执行失败 | session={} step={}", sessionId, context.getStep(), e);
            return finish(
                    context,
                    "Agent 执行失败，请稍后重试。",
                    Status.FAILED);
        }
    }

    private AgentContext createContext(String sessionId) {
        List<AgentContext.Message> history = chatSessionService.getHistory(sessionId).stream()
                .map(message -> new AgentContext.Message(message.role(), message.content()))
                .toList();
        return new AgentContext(sessionId, history);
    }

    private AgentResult finish(AgentContext context, String answer, Status status) {
        String safeAnswer = answer == null || answer.isBlank()
                ? "Agent 未生成有效回答。"
                : answer;
        context.addMessage("assistant", safeAnswer);
        chatSessionService.appendMessage(context.getSessionId(), "assistant", safeAnswer);
        return new AgentResult(
                context.getSessionId(),
                safeAnswer,
                status,
                context.getStep(),
                context.getToolHistory().stream().map(this::toTrace).toList());
    }

    private ToolTrace toTrace(ToolCallRecord record) {
        String observation = toObservation(record);
        boolean success = record.result() != null && record.result().success();
        return new ToolTrace(
                record.toolCallId(),
                record.toolName(),
                record.params(),
                success,
                record.denied(),
                observation);
    }

    private String toObservation(ToolCallRecord record) {
        if (record.denied()) {
            return "工具 " + record.toolName() + " 调用被拒绝：" + record.denyReason();
        }
        if (record.result() == null) {
            return "工具 " + record.toolName() + " 未返回结果。";
        }
        if (record.result().success()) {
            return "工具 " + record.toolName() + " 执行结果："
                    + outputSanitizer.forModel(
                            record.toolName(), record.result().content());
        }
        return "工具 " + record.toolName() + " 执行失败：" + record.result().error();
    }

    private String requireQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        return query.trim();
    }

    private String normalizeToolCallId(String toolCallId) {
        return toolCallId == null || toolCallId.isBlank()
                ? "call_" + UUID.randomUUID().toString().replace("-", "")
                : toolCallId;
    }
}
