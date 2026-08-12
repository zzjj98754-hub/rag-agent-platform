package com.example.demo.controller;

import com.example.demo.agent.AgentExecutor;
import com.example.demo.agent.AgentResult;
import com.example.demo.agent.tool.ToolRegistry;
import com.example.demo.dto.AgentChatRequest;
import com.example.demo.dto.ToolCallRequest;
import com.example.demo.dto.ToolCallResponse;
import com.example.demo.service.ToolCallService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent API 入口。Controller 仅负责协议适配，推理循环和工具调度均在 Service 层。
 */
@RestController
public class AgentController {

    private final AgentExecutor agentExecutor;
    private final ToolCallService toolCallService;
    private final ToolRegistry toolRegistry;

    public AgentController(
            AgentExecutor agentExecutor,
            ToolCallService toolCallService,
            ToolRegistry toolRegistry) {
        this.agentExecutor = agentExecutor;
        this.toolCallService = toolCallService;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 执行完整 Agent Loop。
     */
    @PostMapping("/agent/chat")
    public AgentResult chat(@Valid @RequestBody AgentChatRequest request) {
        return agentExecutor.execute(
                request.getQuery(),
                request.getSessionId());
    }

    /**
     * 保留单次工具调用接口，便于工具独立调试和向后兼容。
     */
    @PostMapping("/agent/tool-call")
    public ToolCallResponse toolCall(@Valid @RequestBody ToolCallRequest request) {
        return toolCallService.execute(request);
    }

    @GetMapping("/agent/tools")
    public Map<String, Object> listTools() {
        return Map.of(
                "tools", toolRegistry.listToolsForLLM(),
                "count", toolRegistry.toolNames().size());
    }
}
