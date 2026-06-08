package com.example.demo.controller;

import com.example.demo.agent.tool.ToolRegistry;
import com.example.demo.agent.tool.ToolScheduler;
import com.example.demo.agent.tool.ToolScheduler.ToolCallRecord;
import com.example.demo.dto.ToolCallRequest;
import com.example.demo.dto.ToolCallResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent Tool Calling 端点。
 *
 * 面试要点：
 * - POST /agent/tool-call —— 单次工具调用
 * - GET /agent/tools —— 获取可用工具列表（给 LLM Function Calling 用）
 * - 每次调用经过 ToolScheduler 统一调度：权限校验 → 超时控制 → 死循环检测 → 执行
 */
@RestController
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    @Autowired
    private ToolRegistry registry;

    @Autowired
    private ToolScheduler scheduler;

    /**
     * 单次工具调用 —— 由 LLM 决策后触发。
     */
    @PostMapping("/agent/tool-call")
    public ToolCallResponse toolCall(@RequestBody ToolCallRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "anon";
        }
        String role = request.getRole();
        if (role == null || role.isBlank()) {
            role = "user";
        }

        // 从会话上下文中获取本轮历史（简化：每次请求独立，历史由前端/Agent 循环管理）
        List<ToolCallRecord> stepHistory = new ArrayList<>();

        ToolCallRecord record = scheduler.dispatch(
                request.getToolName(),
                request.getParams(),
                role,
                sessionId,
                stepHistory
        );

        List<String> toolNames = stepHistory.stream().map(ToolCallRecord::toolName).toList();

        if (record.denied()) {
            return ToolCallResponse.denied(request.getToolName(), record.denyReason(), toolNames);
        }

        if (record.result() != null && record.result().success()) {
            return ToolCallResponse.ok(request.getToolName(),
                    record.result().content(),
                    record.result().elapsedMs(),
                    toolNames);
        }

        return ToolCallResponse.error(request.getToolName(),
                record.result() != null ? record.result().error() : "未知错误",
                toolNames);
    }

    /**
     * 返回可用工具列表 —— 给 LLM Function Calling 使用。
     */
    @GetMapping("/agent/tools")
    public Map<String, Object> listTools() {
        return Map.of(
                "tools", registry.listToolsForLLM(),
                "count", registry.toolNames().size()
        );
    }

}
