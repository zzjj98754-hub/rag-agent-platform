package com.example.demo.persistence;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.demo.persistence.entity.OutboxEventEntity;
import com.example.demo.persistence.mapper.OutboxEventMapper;
import com.example.demo.persistence.service.OutboxRelay;
import com.example.demo.service.ChatCacheProjector;
import java.util.List;
import org.junit.jupiter.api.Test;

class OutboxRelayTest {

    @Test
    void successfulProjectionShouldMarkEventProcessed() throws Exception {
        OutboxEventMapper mapper = mock(OutboxEventMapper.class);
        ChatCacheProjector projector = mock(ChatCacheProjector.class);
        OutboxEventEntity event = event(7L, 0);
        when(mapper.findPending(50)).thenReturn(List.of(event));

        new OutboxRelay(mapper, projector, 50, 8, 5).relay();

        verify(projector).project(event);
        verify(mapper).markProcessed(7L);
    }

    @Test
    void failedProjectionShouldScheduleRetry() throws Exception {
        OutboxEventMapper mapper = mock(OutboxEventMapper.class);
        ChatCacheProjector projector = mock(ChatCacheProjector.class);
        OutboxEventEntity event = event(9L, 2);
        when(mapper.findPending(50)).thenReturn(List.of(event));
        doThrow(new IllegalStateException("redis unavailable"))
                .when(projector).project(event);

        new OutboxRelay(mapper, projector, 50, 8, 5).relay();

        verify(mapper).markRetry(9L, "redis unavailable", 5, 8);
    }

    private OutboxEventEntity event(long id, int retryCount) {
        OutboxEventEntity event = new OutboxEventEntity();
        event.setId(id);
        event.setRetryCount(retryCount);
        return event;
    }
}
