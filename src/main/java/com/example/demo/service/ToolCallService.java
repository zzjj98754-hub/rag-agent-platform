package com.example.demo.service;

import com.example.demo.agent.tool.ToolScheduler;
import com.example.demo.agent.tool.ToolScheduler.ToolCallRecord;
import com.example.demo.dto.ToolCallRequest;
import com.example.demo.dto.ToolCallResponse;
import com.example.demo.security.AuthenticatedUser;
import com.example.demo.security.CurrentUserProvider;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 单次工具调用的应用服务，保留旧接口同时避免 Controller 承担调度逻辑。
 */
@Service
public class ToolCallService {

    private final ToolScheduler scheduler;
    private final CurrentUserProvider currentUserProvider;

    public ToolCallService(
            ToolScheduler scheduler,
            CurrentUserProvider currentUserProvider) {
        this.scheduler = scheduler;
        this.currentUserProvider = currentUserProvider;
    }

    public ToolCallResponse execute(ToolCallRequest request) {
        AuthenticatedUser currentUser = currentUserProvider.requireCurrentUser();
        String sessionId = defaultText(
                request.getSessionId(),
                "user:" + currentUser.id());
        List<ToolCallRecord> history = new ArrayList<>();

        ToolCallRecord record = scheduler.dispatch(
                request.getToolName(),
                request.getParams(),
                currentUser.role(),
                sessionId,
                history);
        history.add(record);
        List<String> toolNames = history.stream().map(ToolCallRecord::toolName).toList();

        if (record.denied()) {
            return ToolCallResponse.denied(request.getToolName(), record.denyReason(), toolNames);
        }
        if (record.result() != null && record.result().success()) {
            return ToolCallResponse.ok(
                    request.getToolName(),
                    record.result().content(),
                    record.result().elapsedMs(),
                    toolNames);
        }
        return ToolCallResponse.error(
                request.getToolName(),
                record.result() != null ? record.result().error() : "未知错误",
                toolNames);
    }

    private String defaultText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
