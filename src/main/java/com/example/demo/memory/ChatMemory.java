package com.example.demo.memory;

import java.util.List;

/** Small platform facade matching Spring AI's chat-memory semantics. */
public interface ChatMemory {
    void add(String conversationId, String role, String content);
    List<Message> get(String conversationId, int maxMessages);
    void clear(String conversationId);
    record Message(String role, String content) {}
}
