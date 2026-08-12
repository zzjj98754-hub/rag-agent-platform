package com.example.demo.service;

import com.example.demo.persistence.entity.OutboxEventEntity;
import com.example.demo.persistence.service.OutboxEventService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Idempotently projects MySQL outbox events into the Redis hot window. */
@Component
public class ChatCacheProjector {

    private static final String SESSION_PREFIX = "chat:session:";
    private static final DefaultRedisScript<Long> SESSION_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('SETNX', KEYS[2], ARGV[1]) == 0 then return 0 end
                    redis.call('EXPIRE', KEYS[2], ARGV[2])
                    redis.call('HSETNX', KEYS[1], 'createdAt', ARGV[3])
                    redis.call('HSETNX', KEYS[1], 'messageCount', '0')
                    redis.call('EXPIRE', KEYS[1], ARGV[2])
                    return 1
                    """, Long.class);
    private static final DefaultRedisScript<Long> MESSAGE_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('SETNX', KEYS[3], ARGV[1]) == 0 then return 0 end
                    redis.call('EXPIRE', KEYS[3], ARGV[2])
                    redis.call('LPUSH', KEYS[1], ARGV[3])
                    redis.call('LTRIM', KEYS[1], 0, tonumber(ARGV[4]) - 1)
                    redis.call('EXPIRE', KEYS[1], ARGV[2])
                    redis.call('HSET', KEYS[2], 'lastActiveAt', ARGV[5])
                    redis.call('HINCRBY', KEYS[2], 'messageCount', 1)
                    redis.call('EXPIRE', KEYS[2], ARGV[2])
                    return 1
                    """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final int maxHistory;
    private final long ttlSeconds;

    public ChatCacheProjector(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Value("${app.session.max-history}") int maxHistory,
            @Value("${app.session.ttl}") long ttlSeconds) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.maxHistory = maxHistory;
        this.ttlSeconds = ttlSeconds;
    }

    public void project(OutboxEventEntity event) throws Exception {
        Map<String, Object> payload = objectMapper.readValue(
                event.getPayload(), new TypeReference<>() { });
        String sessionId = String.valueOf(payload.get("sessionId"));
        String eventKey = "outbox:processed:" + event.getId();
        if (OutboxEventService.SESSION_CREATED.equals(event.getEventType())) {
            redis.execute(SESSION_SCRIPT,
                    List.of(metaKey(sessionId), eventKey),
                    event.getId().toString(), Long.toString(ttlSeconds),
                    String.valueOf(payload.get("timestamp")));
            return;
        }
        if (OutboxEventService.MESSAGE_APPENDED.equals(event.getEventType())) {
            ChatSessionService.Message message = new ChatSessionService.Message(
                    String.valueOf(payload.get("role")),
                    String.valueOf(payload.get("content")),
                    Long.parseLong(String.valueOf(payload.get("timestamp"))));
            redis.execute(MESSAGE_SCRIPT,
                    List.of(messageKey(sessionId), metaKey(sessionId), eventKey),
                    event.getId().toString(), Long.toString(ttlSeconds),
                    objectMapper.writeValueAsString(message),
                    Integer.toString(maxHistory),
                    Long.toString(message.timestamp()));
            return;
        }
        throw new IllegalArgumentException("Unsupported outbox event: " + event.getEventType());
    }

    private String messageKey(String sessionId) {
        return SESSION_PREFIX + sessionId + ":messages";
    }

    private String metaKey(String sessionId) {
        return SESSION_PREFIX + sessionId + ":meta";
    }
}
