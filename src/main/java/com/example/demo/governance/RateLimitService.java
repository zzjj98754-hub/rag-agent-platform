package com.example.demo.governance;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class RateLimitService {
    private final StringRedisTemplate redis;
    private final int limit;
    private final ConcurrentHashMap<String, Window> fallback = new ConcurrentHashMap<>();
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return current
            """, Long.class);
    public RateLimitService(StringRedisTemplate redis,
            @Value("${app.governance.rate-limit-per-minute}") int limit) {
        this.redis = redis; this.limit = Math.max(1, limit);
    }
    public boolean allow(String subject, String api) {
        String key = "rl:" + subject + ":" + api;
        try {
            Long count = redis.execute(SCRIPT, java.util.List.of(key), "60000");
            return count == null || count <= limit;
        } catch (RuntimeException ignored) {
            long now = System.currentTimeMillis();
            Window window = fallback.compute(key, (ignoredKey, current) ->
                    current == null || now - current.startedAt > 60_000
                            ? new Window(now, new AtomicInteger()) : current);
            return window.count.incrementAndGet() <= limit;
        }
    }
    private record Window(long startedAt, AtomicInteger count) {}
}
