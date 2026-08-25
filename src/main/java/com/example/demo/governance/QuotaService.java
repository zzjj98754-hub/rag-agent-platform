package com.example.demo.governance;

import java.time.YearMonth;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class QuotaService {
    private final long monthlyLimit;
    private final StringRedisTemplate redis;
    private final ConcurrentHashMap<String, AtomicLong> usage = new ConcurrentHashMap<>();
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local used = tonumber(redis.call('GET', KEYS[1]) or '0')
            local amount = tonumber(ARGV[1])
            local limit = tonumber(ARGV[2])
            if used + amount > limit then return -1 end
            local next = redis.call('INCRBY', KEYS[1], amount)
            if next == amount then redis.call('EXPIRE', KEYS[1], ARGV[3]) end
            return next
            """, Long.class);
    public QuotaService(
            StringRedisTemplate redis,
            @Value("${app.governance.quota-monthly-tokens}") long monthlyLimit) {
        this.redis = redis;
        this.monthlyLimit = Math.max(1, monthlyLimit);
    }
    public boolean tryConsume(String userId, long tokens) {
        String key = YearMonth.now() + ":" + userId;
        long amount = Math.max(0, tokens);
        try {
            Long result = redis.execute(SCRIPT, java.util.List.of("quota:" + key),
                    String.valueOf(amount), String.valueOf(monthlyLimit), "2678400");
            return result != null && result >= 0;
        } catch (RuntimeException ignored) {
            AtomicLong counter = usage.computeIfAbsent(key, unused -> new AtomicLong());
            while (true) {
                long before = counter.get();
                if (before + amount > monthlyLimit) return false;
                if (counter.compareAndSet(before, before + amount)) return true;
            }
        }
    }
    public long used(String userId) {
        String key = YearMonth.now() + ":" + userId;
        try {
            String value = redis.opsForValue().get("quota:" + key);
            return value == null ? 0 : Long.parseLong(value);
        } catch (RuntimeException ignored) {
            return usage.getOrDefault(key, new AtomicLong()).get();
        }
    }
    public long limit() { return monthlyLimit; }
}
