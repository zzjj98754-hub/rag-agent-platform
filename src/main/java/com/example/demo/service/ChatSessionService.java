package com.example.demo.service;

import com.example.demo.persistence.entity.ChatMessageEntity;
import com.example.demo.persistence.entity.ChatSessionEntity;
import com.example.demo.persistence.service.ChatHistoryPersistenceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 会话上下文存储。
 *
 * <p>Redis 保存最近 20 轮热上下文，MySQL 保存完整历史记录。
 * 本地 ConcurrentHashMap 只在 Redis 和 MySQL 都不可用时提供短期降级。
 */
@Service
public class ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SESSION_PREFIX = "chat:session:";
    private static final String MSG_SUFFIX = ":messages";
    private static final String META_SUFFIX = ":meta";

    private final StringRedisTemplate redisTemplate;
    private final ChatHistoryPersistenceService historyPersistenceService;
    private final int maxHistory;
    private final long sessionTtlSeconds;

    private final Map<String, List<Message>> fallbackStore = new ConcurrentHashMap<>();
    private final Map<String, Long> fallbackOwners = new ConcurrentHashMap<>();
    private final Map<String, Long> fallbackCreatedAt = new ConcurrentHashMap<>();
    private final long fallbackTtlMillis;

    public record Message(String role, String content, long timestamp) {}

    public ChatSessionService(
            StringRedisTemplate redisTemplate,
            ChatHistoryPersistenceService historyPersistenceService,
            @Value("${app.session.max-history}") int maxHistory,
            @Value("${app.session.ttl}") long sessionTtlSeconds) {
        this.redisTemplate = redisTemplate;
        this.historyPersistenceService = historyPersistenceService;
        this.maxHistory = maxHistory;
        this.sessionTtlSeconds = sessionTtlSeconds;
        this.fallbackTtlMillis = Math.max(60_000L, sessionTtlSeconds * 1000L);
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "chat-fallback-cleaner");
            thread.setDaemon(true);
            return thread;
        }).scheduleAtFixedRate(this::cleanupFallback, 1, 1, TimeUnit.HOURS);
    }

    /**
     * 创建新会话，返回 sessionId。
     */
    public String createSession() {
        return createSession(null, "新会话");
    }

    /**
     * 为指定用户创建会话。匿名会话的 userId 可以为空。
     */
    public String createSession(Long userId, String title) {
        String sessionId = UUID.randomUUID().toString().replace("-", "");

        boolean persisted = persistSession(sessionId, userId, title);
        // MySQL + outbox is the source of truth. Redis is updated asynchronously.
        boolean cached = persisted || cacheSessionMeta(sessionId, 0);
        if (!persisted && !cached) {
            fallbackStore.putIfAbsent(
                    sessionId,
                    Collections.synchronizedList(new ArrayList<>()));
            fallbackCreatedAt.put(sessionId, System.currentTimeMillis());
        }
        if (userId != null) {
            fallbackOwners.put(sessionId, userId);
        }
        return sessionId;
    }

    private boolean persistSession(String sessionId, Long userId, String title) {
        try {
            historyPersistenceService.createSession(sessionId, userId, title);
            return true;
        } catch (Exception e) {
            log.error("MySQL 创建会话失败，完整历史暂不可用 | session={}: {}",
                    sessionId, e.getMessage());
            return false;
        }
    }

    private boolean cacheSessionMeta(String sessionId, long messageCount) {
        try {
            String metaKey = metaKey(sessionId);
            redisTemplate.opsForHash().putAll(metaKey, Map.of(
                    "createdAt", String.valueOf(System.currentTimeMillis()),
                    "messageCount", String.valueOf(messageCount)));
            redisTemplate.expire(metaKey, sessionTtl());
            return true;
        } catch (Exception e) {
            log.warn("Redis 创建会话缓存失败 | session={}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    /**
     * 追加消息：MySQL 保存完整记录，Redis 仅保留热窗口。
     */
    public void appendMessage(String sessionId, String role, String content) {
        Message msg = new Message(role, content, System.currentTimeMillis());

        boolean persisted = false;
        try {
            historyPersistenceService.appendMessage(sessionId, role, content);
            persisted = true;
        } catch (Exception e) {
            log.error("MySQL 持久化消息失败 | session={} role={}: {}",
                    sessionId, role, e.getMessage());
        }

        // When MySQL succeeds the outbox relay updates Redis. Direct cache write
        // is only the availability fallback when durable persistence is down.
        if (persisted) {
            return;
        }

        String json = serialize(msg);
        if (json == null) return;

        String msgKey = messageKey(sessionId);
        String metaKey = metaKey(sessionId);

        try {
            redisTemplate.opsForList().leftPush(msgKey, json);
            redisTemplate.opsForList().trim(msgKey, 0, maxHistory - 1);
            redisTemplate.expire(msgKey, sessionTtl());
            redisTemplate.opsForHash().put(metaKey, "lastActiveAt", String.valueOf(System.currentTimeMillis()));
            redisTemplate.opsForHash().increment(metaKey, "messageCount", 1);
            redisTemplate.expire(metaKey, sessionTtl());
        } catch (Exception e) {
            log.warn("Redis 追加消息失败，降级到本地: {}", e.getMessage());
            appendToFallback(sessionId, msg);
        }
    }

    /**
     * 获取热上下文（按时间正序）。Redis 未命中时从 MySQL 最近记录回填。
     */
    public List<Message> getHistory(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }

        try {
            List<Message> redisMessages = readRedisHistory(sessionId);
            if (!redisMessages.isEmpty()) {
                return redisMessages;
            }
        } catch (Exception e) {
            log.warn("Redis 读取历史失败 | session={}: {}", sessionId, e.getMessage());
        }

        try {
            List<Message> persisted = toMessages(
                    historyPersistenceService.getRecentMessages(sessionId, maxHistory));
            if (!persisted.isEmpty()) {
                warmRedis(sessionId, persisted);
            }
            return persisted;
        } catch (Exception e) {
            log.error("MySQL 读取最近历史失败 | session={}: {}", sessionId, e.getMessage());
        }

        List<Message> fallback = fallbackStore.get(sessionId);
        return fallback != null && !fallback.isEmpty()
                ? copySynchronized(fallback)
                : List.of();
    }

    /**
     * 从 MySQL 获取完整历史，供历史查询和审计使用。
     */
    public List<Message> getFullHistory(String sessionId) {
        try {
            return toMessages(historyPersistenceService.getFullHistory(sessionId));
        } catch (Exception e) {
            log.error("MySQL 读取完整历史失败 | session={}: {}", sessionId, e.getMessage());
            return getHistory(sessionId);
        }
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
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(metaKey(sessionId)))) {
                return true;
            }
        } catch (Exception e) {
            log.debug("Redis 检查会话失败 | session={}: {}", sessionId, e.getMessage());
        }
        if (fallbackStore.containsKey(sessionId)) {
            return true;
        }
        try {
            return historyPersistenceService.sessionExists(sessionId);
        } catch (Exception e) {
            log.error("MySQL 检查会话失败 | session={}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    /**
     * 校验会话归属，防止用户通过猜测 sessionId 读取其他用户上下文。
     */
    public boolean canAccess(String sessionId, Long userId, boolean admin) {
        if (sessionId == null || sessionId.isBlank() || userId == null) {
            return false;
        }
        if (admin) {
            return exists(sessionId);
        }
        Long fallbackOwner = fallbackOwners.get(sessionId);
        if (fallbackOwner != null) {
            return Objects.equals(fallbackOwner, userId);
        }
        try {
            ChatSessionEntity session =
                    historyPersistenceService.findSession(sessionId);
            return session != null
                    && Objects.equals(session.getUserId(), userId);
        } catch (Exception e) {
            log.error("MySQL 校验会话归属失败 | session={}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    /**
     * 获取会话元信息。
     */
    public Map<Object, Object> getMeta(String sessionId) {
        try {
            Map<Object, Object> redisMeta =
                    redisTemplate.opsForHash().entries(metaKey(sessionId));
            if (!redisMeta.isEmpty()) {
                return redisMeta;
            }
        } catch (Exception e) {
            log.debug("Redis 读取会话元信息失败 | session={}: {}", sessionId, e.getMessage());
        }
        try {
            if (!historyPersistenceService.sessionExists(sessionId)) {
                return Map.of();
            }
            Map<Object, Object> meta = new LinkedHashMap<>();
            meta.put("storage", "mysql");
            meta.put("messageCount", historyPersistenceService.countMessages(sessionId));
            return meta;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Clear only the Redis hot projection; MySQL remains the source of truth. */
    public void clearHotHistory(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            redisTemplate.delete(messageKey(sessionId));
        } catch (Exception e) {
            log.debug("清理 Redis 会话热窗口失败 | session={}: {}", sessionId, e.getMessage());
        }
    }

    private void cleanupFallback() {
        long cutoff = System.currentTimeMillis() - fallbackTtlMillis;
        fallbackCreatedAt.entrySet().removeIf(entry -> {
            if (entry.getValue() >= cutoff) return false;
            fallbackStore.remove(entry.getKey());
            fallbackOwners.remove(entry.getKey());
            return true;
        });
    }

    private List<Message> readRedisHistory(String sessionId) {
        List<String> raw = redisTemplate.opsForList().range(messageKey(sessionId), 0, -1);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Message> messages = new ArrayList<>(raw.size());
        for (String value : raw) {
            Message message = deserialize(value);
            if (message != null) {
                messages.add(message);
            }
        }
        Collections.reverse(messages);
        return messages;
    }

    private void warmRedis(String sessionId, List<Message> messages) {
        try {
            String msgKey = messageKey(sessionId);
            redisTemplate.delete(msgKey);
            for (Message message : messages) {
                String json = serialize(message);
                if (json != null) {
                    redisTemplate.opsForList().leftPush(msgKey, json);
                }
            }
            redisTemplate.opsForList().trim(msgKey, 0, maxHistory - 1);
            redisTemplate.expire(msgKey, sessionTtl());
            cacheSessionMeta(sessionId, messages.size());
        } catch (Exception e) {
            log.warn("MySQL 历史回填 Redis 失败 | session={}: {}", sessionId, e.getMessage());
        }
    }

    private List<Message> toMessages(List<ChatMessageEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        return entities.stream()
                .map(entity -> new Message(
                        entity.getRole(),
                        entity.getContent(),
                        entity.getCreateTime() == null
                                ? System.currentTimeMillis()
                                : entity.getCreateTime()
                                        .atZone(ZoneId.systemDefault())
                                        .toInstant()
                                        .toEpochMilli()))
                .toList();
    }

    private void appendToFallback(String sessionId, Message message) {
        fallbackCreatedAt.putIfAbsent(sessionId, System.currentTimeMillis());
        List<Message> messages = fallbackStore.computeIfAbsent(
                sessionId,
                key -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (messages) {
            messages.add(message);
            while (messages.size() > maxHistory) {
                messages.remove(0);
            }
        }
    }

    private List<Message> copySynchronized(List<Message> messages) {
        synchronized (messages) {
            return new ArrayList<>(messages);
        }
    }

    private String messageKey(String sessionId) {
        return SESSION_PREFIX + sessionId + MSG_SUFFIX;
    }

    private String metaKey(String sessionId) {
        return SESSION_PREFIX + sessionId + META_SUFFIX;
    }

    private Duration sessionTtl() {
        return Duration.ofSeconds(sessionTtlSeconds);
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
