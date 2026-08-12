package com.example.demo.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.demo.service.ChatSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class AuthenticatedSessionServiceTest {

    private final ChatSessionService chatSessionService =
            mock(ChatSessionService.class);
    private final CurrentUserProvider currentUserProvider =
            mock(CurrentUserProvider.class);
    private final AuthenticatedSessionService service =
            new AuthenticatedSessionService(
                    chatSessionService,
                    currentUserProvider);

    @Test
    void shouldBindNewSessionToAuthenticatedUser() {
        AuthenticatedUser user =
                new AuthenticatedUser(7L, "alice", UserRole.USER);
        when(chatSessionService.createSession(7L, "RAG 会话"))
                .thenReturn("new-session");

        String sessionId = service.resolveOrCreate(null, "RAG 会话", user);

        assertThat(sessionId).isEqualTo("new-session");
        verify(chatSessionService).createSession(7L, "RAG 会话");
    }

    @Test
    void shouldRejectSessionOwnedByAnotherUser() {
        AuthenticatedUser user =
                new AuthenticatedUser(7L, "alice", UserRole.USER);
        when(chatSessionService.exists("other-session")).thenReturn(true);
        when(chatSessionService.canAccess("other-session", 7L, false))
                .thenReturn(false);

        assertThatThrownBy(() -> service.resolveOrCreate(
                "other-session",
                "RAG 会话",
                user))
                .isInstanceOf(AccessDeniedException.class);
    }
}
