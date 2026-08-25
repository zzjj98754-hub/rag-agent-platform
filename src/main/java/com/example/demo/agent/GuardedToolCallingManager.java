package com.example.demo.agent;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

/** Official Spring AI manager boundary; execution is guarded by ToolCallbacks. */
public final class GuardedToolCallingManager implements ToolCallingManager {
    private final ToolCallingManager delegate = ToolCallingManager.builder().build();

    @Override
    public java.util.List<org.springframework.ai.tool.definition.ToolDefinition>
            resolveToolDefinitions(ToolCallingChatOptions options) {
        return delegate.resolveToolDefinitions(options);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
        return delegate.executeToolCalls(prompt, response);
    }
}
