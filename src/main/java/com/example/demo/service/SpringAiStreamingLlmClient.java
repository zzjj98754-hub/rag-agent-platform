package com.example.demo.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/** Production streaming transport backed by the same Spring AI ChatClient. */
@Service
@ConditionalOnProperty(name = "app.spring-ai.enabled", havingValue = "true")
public class SpringAiStreamingLlmClient implements StreamingLlmClient {
    private final ChatClient chatClient;

    public SpringAiStreamingLlmClient(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public Flux<String> streamChat(String prompt) {
        return chatClient.prompt(prompt).stream().content();
    }
}
