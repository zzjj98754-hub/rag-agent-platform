package com.example.demo.service;

import com.example.demo.observability.RagObservability;
import com.example.demo.security.AuthenticatedSessionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String CACHE_PREFIX = "chat:cache:";
    private static final String DIGEST_ALGO = "SHA-256";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AuthenticatedSessionService authenticatedSessionService;
    private final LlmClient llmClient;
    private final RagPromptService ragPromptService;
    private final ConversationCompletionService completionService;
    private final RagObservability ragObservability;
    private final String llmModel;
    private final long cacheTtlSeconds;
    private final double ttlJitter;

    // Redis 不可用时降级到本地缓存
    private final Map<String, String> fallbackCache = new ConcurrentHashMap<>();

    // 缓存统计
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();

    public ChatService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            AuthenticatedSessionService authenticatedSessionService,
            LlmClient llmClient,
            RagPromptService ragPromptService,
            ConversationCompletionService completionService,
            RagObservability ragObservability,
            @Value("${app.llm.model}") String llmModel,
            @Value("${app.cache.ttl}") long cacheTtlSeconds,
            @Value("${app.cache.ttl-jitter}") double ttlJitter) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.authenticatedSessionService =
                authenticatedSessionService;
        this.llmClient = llmClient;
        this.ragPromptService = ragPromptService;
        this.completionService = completionService;
        this.ragObservability = ragObservability;
        this.llmModel = llmModel;
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.ttlJitter = ttlJitter;
    }

    /**
     * 缓存值元数据 —— JSON 序列化，含时间戳方便排查。
     */
    public record CacheEntry(String value, long cachedAt, long ttlSeconds) {}

    // ==================== RAG Pipeline ====================

    /**
     * 同步问答 —— 集成 Redis 会话上下文。
     * sessionId 为空时退化为无状态单轮问答。
     */
    public String ask(String query, String sessionId) {
        return ragObservability.observeRequest(
                query,
                () -> doAsk(query, sessionId));
    }

    private String doAsk(String query, String sessionId) {
        // 0. 处理会话上下文
        String effectiveSid = resolveSessionId(sessionId);
        PreparedRagPrompt prepared =
                ragPromptService.prepare(query, effectiveSid);

        // 2. 缓存 key = 命名空间 + hash(query + docs 指纹 + session)
        String cacheKey = buildCacheKey(
                query,
                prepared.documents().hashCode());

        // 3. 查 Redis 缓存（不可用时降级到本地缓存）
        //    注意：多轮对话的缓存 key 不变，但上下文不同 → 需要加入 session hash
        if (effectiveSid != null) {
            cacheKey = buildCacheKey(
                    query + "|s:" + effectiveSid,
                    prepared.documents().hashCode());
        }
        CacheHitResult cached = getFromCache(cacheKey);
        if (cached != null) {
            ragObservability.markCacheHit();
            long hitCount = cacheHits.incrementAndGet();
            long missCount = cacheMisses.get();
            long total = hitCount + missCount;
            double hitRate = total > 0
                    ? (double) hitCount / total * 100
                    : 0;
            log.info("缓存命中 | key={} | 总计 hits={} misses={} | 命中率={}%",
                    cacheKey, hitCount, missCount, String.format("%.1f", hitRate));
            String source = cached.fromRedis() ? "Redis缓存" : "本地缓存(降级)";
            long ageSec = (System.currentTimeMillis() - cached.cachedAt()) / 1000;
            return cached.value() + " (来自" + source + ", 已缓存" + ageSec + "秒)";
        }
        long missCount = cacheMisses.incrementAndGet();
        log.info(
                "缓存未命中 | key={} | 总计 hits={} misses={}",
                cacheKey,
                cacheHits.get(),
                missCount);

        // 5. 调用大模型（模拟）
        String response =
                llmClient.callLlm(prepared.prompt(), llmModel);

        // 6. 写入 Redis 缓存（带 TTL 抖动，不可用时降级到本地）
        putToCache(cacheKey, response);

        // 7. 保存本轮对话到会话上下文
        completionService.complete(prepared, response);

        return response + " (首次生成)";
    }

    /**
     * 校验请求中的会话归属；空 sessionId 表示无状态同步问答。
     */
    private String resolveSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return authenticatedSessionService.resolveOrCreate(
                sessionId,
                "RAG 会话");
    }

    /**
     * 缓存 key = 前缀 + SHA-256(query + "|" + docsHash) 取前 16 位
     * 优点：定长、无特殊字符、不可读输入不暴露在 key 中
     */
    private String buildCacheKey(String query, int docsHash) {
        String raw = query + "|" + docsHash;
        String hash = sha256Hex(raw).substring(0, 16);
        return CACHE_PREFIX + hash;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance(DIGEST_ALGO);
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /** 含反序列化元数据的命中结果 */
    private record CacheHitResult(String value, long cachedAt, boolean fromRedis) {}

    private CacheHitResult getFromCache(String key) {
        try {
            String raw = redisTemplate.opsForValue().get(key);
            if (raw != null) {
                CacheEntry entry = objectMapper.readValue(raw, CacheEntry.class);
                return new CacheHitResult(entry.value(), entry.cachedAt(), true);
            }
        } catch (Exception e) {
            log.warn("Redis 读取失败，降级到本地缓存: {}", e.getMessage());
        }
        // Redis 不可用或未命中，尝试本地缓存
        String fallback = fallbackCache.get(key);
        if (fallback != null) {
            // 本地缓存为纯字符串，无元数据
            return new CacheHitResult(fallback, 0, false);
        }
        return null;
    }

    private void putToCache(String key, String value) {
        CacheEntry entry = new CacheEntry(value, System.currentTimeMillis(), cacheTtlSeconds);
        String json;
        try {
            json = objectMapper.writeValueAsString(entry);
        } catch (JsonProcessingException e) {
            log.warn("缓存序列化失败，降级为纯字符串: {}", e.getMessage());
            json = value;
        }

        long ttl = effectiveTtl();
        try {
            redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(ttl));
        } catch (Exception e) {
            log.warn("Redis 写入失败，降级到本地缓存: {}", e.getMessage());
            fallbackCache.put(key, value);
        }
    }

    /**
     * TTL 抖动 —— 在 base_ttl * (1 ± jitter%) 范围内随机浮动。
     * 避免同一时刻大量缓存同时过期导致缓存雪崩。
     */
    private long effectiveTtl() {
        double jitter = ttlJitter * (ThreadLocalRandom.current().nextDouble() * 2 - 1); // [-jitter, +jitter]
        return Math.max(60, (long) (cacheTtlSeconds * (1 + jitter)));
    }

}
