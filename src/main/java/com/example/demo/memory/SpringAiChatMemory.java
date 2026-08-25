package com.example.demo.memory;

import com.example.demo.service.ChatSessionService;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

/** ChatMemory facade backed by the existing MySQL + Outbox + Redis projection. */
@Service
public class SpringAiChatMemory implements org.springframework.ai.chat.memory.ChatMemory {
    private final ChatSessionService sessions;
    public SpringAiChatMemory(ChatSessionService sessions) { this.sessions = sessions; }

    @Override
    public void add(String conversationId, List<Message> messages) {
        for (Message message : messages) {
            sessions.appendMessage(
                    conversationId,
                    message.getMessageType().getValue(),
                    message.getText());
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        return sessions.getHistory(conversationId).stream()
                .map(item -> toSpringMessage(item.role(), item.content()))
                .toList();
    }

    @Override
    public void clear(String conversationId) {
        // Durable history remains the audit source; clear only removes the hot projection.
        sessions.clearHotHistory(conversationId);
    }

    private Message toSpringMessage(String role, String content) {
        return switch (role == null ? "user" : role.toLowerCase()) {
            case "assistant" -> new AssistantMessage(content);
            case "system" -> new SystemMessage(content);
            default -> new UserMessage(content);
        };
    }
}
