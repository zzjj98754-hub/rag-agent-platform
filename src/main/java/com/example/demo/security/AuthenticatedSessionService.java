package com.example.demo.security;

import com.example.demo.service.ChatSessionService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * 用户会话访问边界：创建会话时绑定 userId，访问已有会话时校验归属。
 */
@Service
public class AuthenticatedSessionService {

    private final ChatSessionService chatSessionService;
    private final CurrentUserProvider currentUserProvider;

    public AuthenticatedSessionService(
            ChatSessionService chatSessionService,
            CurrentUserProvider currentUserProvider) {
        this.chatSessionService = chatSessionService;
        this.currentUserProvider = currentUserProvider;
    }

    public String resolveOrCreate(String requestedSessionId, String title) {
        return resolveOrCreate(
                requestedSessionId,
                title,
                currentUserProvider.requireCurrentUser());
    }

    public String resolveOrCreate(
            String requestedSessionId,
            String title,
            AuthenticatedUser currentUser) {
        if (requestedSessionId == null
                || requestedSessionId.isBlank()
                || !chatSessionService.exists(requestedSessionId)) {
            return chatSessionService.createSession(currentUser.id(), title);
        }
        boolean admin = currentUser.role() == UserRole.ADMIN;
        if (!chatSessionService.canAccess(
                requestedSessionId,
                currentUser.id(),
                admin)) {
            throw new AccessDeniedException("无权访问该会话");
        }
        return requestedSessionId;
    }
}
