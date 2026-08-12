package com.example.demo.service;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;

/**
 * 进程内 SSE 短期重放缓冲。它只解决短连接抖动，不是持久化消息队列。
 */
public class SseReplayBuffer {

    public record BufferedEvent(String id, String name, Object data) {
    }

    private final ConcurrentHashMap<String, SessionBuffer> sessions =
            new ConcurrentHashMap<>();
    private final int maxEvents;
    private final long ttlMillis;
    private final Clock clock;

    public SseReplayBuffer(
            @Value("${app.llm.streaming.replay.max-events}")
                    int maxEvents,
            @Value("${app.llm.streaming.replay.ttl-ms}")
                    long ttlMillis) {
        this(maxEvents, ttlMillis, Clock.systemUTC());
    }

    SseReplayBuffer(int maxEvents, long ttlMillis, Clock clock) {
        this.maxEvents = Math.max(1, maxEvents);
        this.ttlMillis = Math.max(1_000, ttlMillis);
        this.clock = clock;
    }

    public String nextId(String sessionId) {
        return Long.toString(buffer(sessionId).sequence.incrementAndGet());
    }

    public void append(
            String sessionId,
            String id,
            String event,
            Object data) {
        SessionBuffer buffer = buffer(sessionId);
        synchronized (buffer) {
            buffer.lastAccessMillis = clock.millis();
            buffer.events.addLast(new BufferedEvent(id, event, data));
            while (buffer.events.size() > maxEvents) {
                buffer.events.removeFirst();
            }
        }
        evictExpired();
    }

    public List<BufferedEvent> eventsAfter(
            String sessionId,
            String lastEventId) {
        long lastId = parseId(lastEventId);
        if (lastId < 0) {
            return List.of();
        }
        SessionBuffer buffer = sessions.get(sessionId);
        if (buffer == null) {
            return List.of();
        }
        synchronized (buffer) {
            buffer.lastAccessMillis = clock.millis();
            List<BufferedEvent> replay = new ArrayList<>();
            for (BufferedEvent event : buffer.events) {
                if (parseId(event.id()) > lastId) {
                    replay.add(event);
                }
            }
            return List.copyOf(replay);
        }
    }

    public boolean hasEvents(String sessionId) {
        SessionBuffer buffer = sessions.get(sessionId);
        if (buffer == null) {
            return false;
        }
        synchronized (buffer) {
            return !buffer.events.isEmpty();
        }
    }

    public String latestEventName(String sessionId) {
        SessionBuffer buffer = sessions.get(sessionId);
        if (buffer == null) {
            return null;
        }
        synchronized (buffer) {
            BufferedEvent latest = buffer.events.peekLast();
            return latest == null ? null : latest.name();
        }
    }

    private SessionBuffer buffer(String sessionId) {
        return sessions.computeIfAbsent(
                sessionId,
                ignored -> new SessionBuffer(clock.millis()));
    }

    private void evictExpired() {
        long cutoff = clock.millis() - ttlMillis;
        sessions.entrySet().removeIf(entry ->
                entry.getValue().lastAccessMillis < cutoff);
    }

    private long parseId(String id) {
        if (id == null || id.isBlank()) {
            return -1;
        }
        try {
            return Long.parseLong(id.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static final class SessionBuffer {
        private final AtomicLong sequence = new AtomicLong();
        private final ArrayDeque<BufferedEvent> events = new ArrayDeque<>();
        private volatile long lastAccessMillis;

        private SessionBuffer(long now) {
            this.lastAccessMillis = now;
        }
    }
}
