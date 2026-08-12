package com.example.demo.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Web 层可调用的流式聊天用例。
 */
public interface StreamingChatUseCase {

    default SseEmitter streamChat(String query, String sessionId) {
        return streamChat(query, sessionId, null);
    }

    SseEmitter streamChat(
            String query,
            String sessionId,
            String lastEventId);
}
