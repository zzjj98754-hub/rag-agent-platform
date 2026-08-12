package com.example.demo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.demo.persistence.entity.ChatMessageEntity;
import com.example.demo.persistence.service.ChatHistoryPersistenceService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class ChatSessionServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ListOperations<String, String> listOperations;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private ChatHistoryPersistenceService historyPersistenceService;

    private ChatSessionService service;

    @BeforeEach
    void setUp() {
        service = new ChatSessionService(
                redisTemplate,
                historyPersistenceService,
                40,
                604800);
    }

    @Test
    void appendShouldPersistAndLetOutboxRelayUpdateRedis() {
        service.appendMessage("session-1", "user", "hello");

        verify(historyPersistenceService)
                .appendMessage("session-1", "user", "hello");
        verifyNoInteractions(listOperations);
    }

    @Test
    void redisMissShouldLoadRecentHistoryFromMysql() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(listOperations.range("chat:session:session-2:messages", 0, -1))
                .thenReturn(List.of());
        ChatMessageEntity message = new ChatMessageEntity();
        message.setRole("assistant");
        message.setContent("persisted answer");
        message.setCreateTime(LocalDateTime.now());
        when(historyPersistenceService.getRecentMessages("session-2", 40))
                .thenReturn(List.of(message));

        List<ChatSessionService.Message> history = service.getHistory("session-2");

        assertThat(history)
                .extracting(ChatSessionService.Message::content)
                .containsExactly("persisted answer");
        verify(historyPersistenceService).getRecentMessages("session-2", 40);
    }
}
