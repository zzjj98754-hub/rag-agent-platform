package com.example.demo.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.demo.persistence.entity.ChatMessageEntity;
import com.example.demo.persistence.entity.ChatSessionEntity;
import com.example.demo.persistence.mapper.ChatMessageMapper;
import com.example.demo.persistence.mapper.ChatSessionMapper;
import com.example.demo.persistence.service.ChatHistoryPersistenceService;
import com.example.demo.persistence.service.OutboxEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatHistoryPersistenceServiceTest {

    @Mock
    private ChatSessionMapper chatSessionMapper;

    @Mock
    private ChatMessageMapper chatMessageMapper;

    @Mock
    private OutboxEventService outboxEventService;

    @Captor
    private ArgumentCaptor<ChatMessageEntity> messageCaptor;

    @Test
    void appendMessageShouldPersistAgainstInternalSessionPrimaryKey() {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(42L);
        session.setSessionId("external-session-id");
        when(chatSessionMapper.findBySessionId("external-session-id"))
                .thenReturn(session);
        when(chatMessageMapper.insert(any(ChatMessageEntity.class))).thenReturn(1);

        ChatHistoryPersistenceService service =
                new ChatHistoryPersistenceService(
                        chatSessionMapper, chatMessageMapper, outboxEventService);

        service.appendMessage("external-session-id", "USER", "你好");

        verify(chatMessageMapper).insert(messageCaptor.capture());
        ChatMessageEntity persisted = messageCaptor.getValue();
        assertThat(persisted.getSessionId()).isEqualTo(42L);
        assertThat(persisted.getRole()).isEqualTo("user");
        assertThat(persisted.getContent()).isEqualTo("你好");
        verify(chatSessionMapper).touch(42L);
        verify(outboxEventService).messageAppended(
                org.mockito.ArgumentMatchers.eq("external-session-id"),
                org.mockito.ArgumentMatchers.eq("user"),
                org.mockito.ArgumentMatchers.eq("你好"),
                org.mockito.ArgumentMatchers.anyLong());
    }
}
