package com.example.demo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 会话上下文存储 —— Redis 为主，本地 ConcurrentHashMap 为降级。
 *
 * 面试要点：
 * - Redis List 实现滑动窗口：LPUSH 写 + LTRIM 裁剪，O(1) 追加，O(N) 裁剪
 * - 独立 TTL 防止僵尸会话堆积内存
 * - 降级策略：Redis 不可用时切本地缓存，保证核心链路可用
 */
@Service
public class ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SESSION_PREFIX = "chat:session:";
    private static final String MSG_SUFFIX = ":messages";
    private static final String META_SUFFIX = ":meta";

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${app.session.max-history:20}")
    private int maxHistory;

    @Value("${app.session.ttl:604800}")
    private long sessionTtlSeconds;

    private final Map<String, List<Message>> fallbackStore = new ConcurrentHashMap<>();

    public record Message(String role, String content, long timestamp) {}

    /**
     * 创建新会话，返回 sessionId。
     */
    public String createSession() {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        try {
            String metaKey = SESSION_PREFIX + sessionId + META_SUFFIX;
            Map<String, String> meta = Map.of(
                    "createdAt", String.valueOf(System.currentTimeMillis()),
                    "messageCount", "0"
            );
            redisTemplate.opsForHash().putAll(metaKey, meta);
            redisTemplate.expire(metaKey, Duration.ofSeconds(sessionTtlSeconds));
        } catch (Exception e) {
            log.warn("Redis 创建会话失败，使用本地存储: {}", e.getMessage());
        }
        return sessionId;
    }

    /**
     * 追加一条消息到会话历史（滑动窗口裁剪）。
     */
    public void appendMessage(String sessionId, String role, String content) {
        Message msg = new Message(role, content, System.currentTimeMillis());
        String json = serialize(msg);
        if (json == null) return;

        String msgKey = SESSION_PREFIX + sessionId + MSG_SUFFIX;
        String metaKey = SESSION_PREFIX + sessionId + META_SUFFIX;

        try {
            redisTemplate.opsForList().leftPush(msgKey, json);
            redisTemplate.opsForList().trim(msgKey, 0, maxHistory - 1);
            redisTemplate.expire(msgKey, Duration.ofSeconds(sessionTtlSeconds));
            redisTemplate.opsForHash().put(metaKey, "lastActiveAt", String.valueOf(System.currentTimeMillis()));
            redisTemplate.expire(metaKey, Duration.ofSeconds(sessionTtlSeconds));
        } catch (Exception e) {
            log.warn("Redis 追加消息失败，降级到本地: {}", e.getMessage());
            fallbackStore.computeIfAbsent(sessionId, k -> Collections.synchronizedList(new ArrayList<>())).add(msg);
            List<Message> list = fallbackStore.get(sessionId);
            while (list.size() > maxHistory) {
                list.remove(0);
            }
        }
    }

    /**
     * 获取会话历史（按时间正序）。
     */
    public List<Message> getHistory(String sessionId) {
        String msgKey = SESSION_PREFIX + sessionId + MSG_SUFFIX;
        try {
            List<String> raw = redisTemplate.opsForList().range(msgKey, 0, -1);
            if (raw != null && !raw.isEmpty()) {
                List<Message> messages = new ArrayList<>(raw.size());
                for (String s : raw) {
                    Message m = deserialize(s);
                    if (m != null) messages.add(m);
                }
                Collections.reverse(messages);
                return messages;
            }
        } catch (Exception e) {
            log.warn("Redis 读取历史失败，尝试本地: {}", e.getMessage());
        }
        List<Message> fallback = fallbackStore.get(sessionId);
        return fallback != null ? new ArrayList<>(fallback) : List.of();
    }

    /**
     * 获取会话最近 N 条消息。
     */
    public List<Message> getRecentHistory(String sessionId, int n) {
        List<Message> all = getHistory(sessionId);
        if (all.size() <= n) return all;
        return all.subList(all.size() - n, all.size());
    }

    /**
     * 将会话历史格式化为 prompt 可用的文本。
     */
    public String formatHistory(String sessionId) {
        List<Message> history = getHistory(sessionId);
        if (history.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("--- 历史对话 ---\n");
        for (Message m : history) {
            String label = "user".equals(m.role()) ? "用户" : "助手";
            sb.append(label).append(": ").append(m.content()).append("\n");
        }
        sb.append("--- 历史结束 ---\n\n");
        return sb.toString();
    }

    /**
     * 检查会话是否存在。
     */
    public boolean exists(String sessionId) {
        try {
            String metaKey = SESSION_PREFIX + sessionId + META_SUFFIX;
            return Boolean.TRUE.equals(redisTemplate.hasKey(metaKey));
        } catch (Exception e) {
            return fallbackStore.containsKey(sessionId);
        }
    }

    /**
     * 获取会话元信息。
     */
    public Map<Object, Object> getMeta(String sessionId) {
        try {
            String metaKey = SESSION_PREFIX + sessionId + META_SUFFIX;
            return redisTemplate.opsForHash().entries(metaKey);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String serialize(Message msg) {
        try {
            return objectMapper.writeValueAsString(msg);
        } catch (JsonProcessingException e) {
            log.warn("消息序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private Message deserialize(String json) {
        try {
            return objectMapper.readValue(json, Message.class);
        } catch (JsonProcessingException e) {
            log.warn("消息反序列化失败: {}", e.getMessage());
            return null;
        }
    }

}
