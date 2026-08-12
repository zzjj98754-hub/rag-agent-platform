package com.example.demo.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SseReplayBufferTest {

    @Test
    void lastEventIdReturnsOnlySubsequentEventsInOrder() {
        SseReplayBuffer buffer = new SseReplayBuffer(
                10,
                60_000,
                Clock.systemUTC());
        String first = buffer.nextId("s1");
        buffer.append("s1", first, "token", Map.of("content", "A"));
        String second = buffer.nextId("s1");
        buffer.append("s1", second, "token", Map.of("content", "B"));
        String third = buffer.nextId("s1");
        buffer.append("s1", third, "done", Map.of());

        assertThat(buffer.eventsAfter("s1", first))
                .extracting(SseReplayBuffer.BufferedEvent::id)
                .containsExactly(second, third);
        assertThat(buffer.latestEventName("s1")).isEqualTo("done");
    }
}
