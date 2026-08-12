package com.example.demo.persistence.service;

import com.example.demo.persistence.entity.ChatMessageEntity;
import com.example.demo.persistence.entity.ChatSessionEntity;
import com.example.demo.persistence.mapper.ChatMessageMapper;
import com.example.demo.persistence.mapper.ChatSessionMapper;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatHistoryPersistenceService {

    private static final String DEFAULT_TITLE = "新会话";
    private static final Set<String> SUPPORTED_ROLES =
            Set.of("user", "assistant", "system", "tool");

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final OutboxEventService outboxEventService;

    public ChatHistoryPersistenceService(
            ChatSessionMapper chatSessionMapper,
            ChatMessageMapper chatMessageMapper,
            OutboxEventService outboxEventService) {
        this.chatSessionMapper = chatSessionMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.outboxEventService = outboxEventService;
    }

    @Transactional
    public ChatSessionEntity createSession(
            String externalSessionId,
            Long userId,
            String title) {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setUserId(userId);
        session.setSessionId(requireSessionId(externalSessionId));
        session.setTitle(normalizeTitle(title));
        chatSessionMapper.upsert(session);
        outboxEventService.sessionCreated(
                session.getSessionId(), System.currentTimeMillis());
        return chatSessionMapper.findBySessionId(session.getSessionId());
    }

    @Transactional
    public ChatMessageEntity appendMessage(
            String externalSessionId,
            String role,
            String content) {
        ChatSessionEntity session = ensureSession(externalSessionId);

        ChatMessageEntity message = new ChatMessageEntity();
        message.setSessionId(session.getId());
        message.setRole(normalizeRole(role));
        message.setContent(requireContent(content));
        chatMessageMapper.insert(message);
        chatSessionMapper.touch(session.getId());
        outboxEventService.messageAppended(
                externalSessionId,
                message.getRole(),
                message.getContent(),
                System.currentTimeMillis());
        return message;
    }

    public boolean sessionExists(String externalSessionId) {
        if (externalSessionId == null || externalSessionId.isBlank()) {
            return false;
        }
        return chatSessionMapper.findBySessionId(externalSessionId) != null;
    }

    public ChatSessionEntity findSession(String externalSessionId) {
        return findSessionInternal(externalSessionId);
    }

    public List<ChatMessageEntity> getRecentMessages(
            String externalSessionId,
            int limit) {
        ChatSessionEntity session = findSessionInternal(externalSessionId);
        if (session == null) {
            return List.of();
        }
        return chatMessageMapper.findRecentBySessionId(
                session.getId(),
                Math.max(1, limit));
    }

    public List<ChatMessageEntity> getFullHistory(String externalSessionId) {
        ChatSessionEntity session = findSessionInternal(externalSessionId);
        if (session == null) {
            return List.of();
        }
        return chatMessageMapper.findAllBySessionId(session.getId());
    }

    public long countMessages(String externalSessionId) {
        ChatSessionEntity session = findSessionInternal(externalSessionId);
        return session == null ? 0 : chatMessageMapper.countBySessionId(session.getId());
    }

    @Transactional
    public void updateTitle(String externalSessionId, String title) {
        if (chatSessionMapper.updateTitle(
                requireSessionId(externalSessionId),
                normalizeTitle(title)) == 0) {
            throw new IllegalArgumentException("会话不存在: " + externalSessionId);
        }
    }

    private ChatSessionEntity ensureSession(String externalSessionId) {
        ChatSessionEntity existing = findSessionInternal(externalSessionId);
        return existing != null
                ? existing
                : createSession(externalSessionId, null, DEFAULT_TITLE);
    }

    private ChatSessionEntity findSessionInternal(String externalSessionId) {
        if (externalSessionId == null || externalSessionId.isBlank()) {
            return null;
        }
        return chatSessionMapper.findBySessionId(externalSessionId);
    }

    private String normalizeRole(String role) {
        String normalized = requireText(role, "role").toLowerCase(Locale.ROOT);
        if (!SUPPORTED_ROLES.contains(normalized)) {
            throw new IllegalArgumentException("不支持的消息角色: " + role);
        }
        return normalized;
    }

    private String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return DEFAULT_TITLE;
        }
        return requireText(title, "title", 255);
    }

    private String requireSessionId(String sessionId) {
        return requireText(sessionId, "sessionId", 64);
    }

    private String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        return content;
    }

    private String requireText(String value, String fieldName) {
        return requireText(value, fieldName, Integer.MAX_VALUE);
    }

    private String requireText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    fieldName + " 不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }
}
