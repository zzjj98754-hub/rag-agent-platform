package com.example.demo.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Primary;

/**
 * Migration facade for Spring AI ChatClient. Existing callers depend on
 * LlmClient, so provider replacement remains one bean boundary during rollout.
 * The current implementation delegates to the configured client until the
 * external provider is switched on in production.
 */
@Component
@Primary
public class SpringAiLlmClient implements LlmClient {
    private final ExternalLlmClient delegate;
    private final ChatClient chatClient;
    private final boolean enabled;
    public SpringAiLlmClient(
            ExternalLlmClient delegate,
            ObjectProvider<ChatClient.Builder> builders,
            @Value("${app.spring-ai.enabled}") boolean enabled) {
        this.delegate = delegate;
        ChatClient.Builder builder = builders.getIfAvailable();
        this.chatClient = builder == null ? null : builder.build();
        this.enabled = enabled && chatClient != null;
    }
    @Override public String callLlm(String prompt, String model) {
        if (enabled) {
            try {
                return chatClient.prompt(prompt).call().content();
            } catch (RuntimeException ignored) {
                // Existing timeout/fallback path remains the last line of defence.
            }
        }
        return delegate.callLlm(prompt, model);
    }

    public String callAgent(
            String prompt,
            List<ToolCallback> callbacks,
            Map<String, Object> toolContext) {
        if (!enabled) {
            return delegate.callLlm(prompt, "agent");
        }
        return chatClient.prompt()
                .user(prompt)
                .toolCallbacks(callbacks)
                .toolContext(toolContext)
                .call()
                .content();
    }
}
