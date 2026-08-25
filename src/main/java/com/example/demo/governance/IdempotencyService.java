package com.example.demo.governance;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Service
public class IdempotencyService {
    private final StringRedisTemplate redis;
    private final long ttlSeconds;
    private final Map<String, String> fallback = new ConcurrentHashMap<>();
    private static final String IN_PROGRESS = "__IN_PROGRESS__";
    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if not value then
              redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
              return 1
            end
            if value == ARGV[1] then return 0 end
            return 2
            """, Long.class);
    public IdempotencyService(StringRedisTemplate redis,
            @Value("${app.governance.idempotency-ttl-seconds}") long ttlSeconds) {
        this.redis = redis; this.ttlSeconds = Math.max(60, ttlSeconds);
    }
    public String get(String key) {
        if (key == null || key.isBlank()) return null;
        try { return redis.opsForValue().get("idem:" + key); }
        catch (RuntimeException ignored) { return fallback.get(key); }
    }
    public boolean putIfAbsent(String key, String response) {
        if (key == null || key.isBlank()) return true;
        try {
            Boolean inserted = redis.opsForValue().setIfAbsent("idem:" + key, response, Duration.ofSeconds(ttlSeconds));
            return Boolean.TRUE.equals(inserted);
        } catch (RuntimeException ignored) { return fallback.putIfAbsent(key, response) == null; }
    }

    public Reservation reserve(String key) {
        if (key == null || key.isBlank()) return Reservation.NEW;
        try {
            Long state = redis.execute(RESERVE, java.util.List.of("idem:" + key),
                    IN_PROGRESS, String.valueOf(ttlSeconds));
            return state != null && state == 1 ? Reservation.NEW
                    : state != null && state == 2 ? Reservation.COMPLETED
                    : Reservation.IN_PROGRESS;
        } catch (RuntimeException ignored) {
            String previous = fallback.putIfAbsent(key, IN_PROGRESS);
            return previous == null ? Reservation.NEW
                    : IN_PROGRESS.equals(previous) ? Reservation.IN_PROGRESS
                    : Reservation.COMPLETED;
        }
    }

    public void commit(String key, String response) {
        if (key == null || key.isBlank()) return;
        try {
            redis.opsForValue().set("idem:" + key, response,
                    Duration.ofSeconds(ttlSeconds));
        } catch (RuntimeException ignored) {
            fallback.put(key, response);
        }
    }

    public void release(String key) {
        if (key == null || key.isBlank()) return;
        try { redis.delete("idem:" + key); }
        catch (RuntimeException ignored) { fallback.remove(key, IN_PROGRESS); }
    }

    public enum Reservation { NEW, IN_PROGRESS, COMPLETED }
}
