package com.example.demo.service;

import com.example.demo.rag.CitationFormatter;
import com.example.demo.rag.CitationValidator;
import com.example.demo.rag.HybridRetriever;
import com.example.demo.rag.QueryRewriter;
import com.example.demo.rag.RelevanceGate;
import com.example.demo.rag.SearchResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String CACHE_PREFIX = "chat:cache:";
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String DIGEST_ALGO = "SHA-256";

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private ChatSessionService sessionService;

    @Autowired
    private ExternalLlmClient llmClient;

    @Autowired
    private HybridRetriever hybridRetriever;

    @Autowired
    private QueryRewriter queryRewriter;

    @Autowired
    private CitationFormatter citationFormatter;

    @Autowired
    private CitationValidator citationValidator;

    @Autowired
    private RelevanceGate relevanceGate;

    @Value("${app.cache.ttl:3600}")
    private long cacheTtlSeconds;

    @Value("${app.cache.ttl-jitter:0.1}")
    private double ttlJitter;

    @Value("${app.rag.top-k:3}")
    private int topK;

    // Redis 不可用时降级到本地缓存
    private final Map<String, String> fallbackCache = new ConcurrentHashMap<>();

    // 缓存统计
    private long cacheHits = 0;
    private long cacheMisses = 0;

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
        // 0. 处理会话上下文
        String effectiveSid = resolveSessionId(sessionId);
        String historyText = "";
        if (effectiveSid != null) {
            historyText = sessionService.formatHistory(effectiveSid);
        }

        // 0.5 Query Rewrite：利用历史做指代消解和上下文补充
        String rewrittenQuery = queryRewriter.rewrite(query, historyText);

        // 1. 混合检索 Top-K 相关文档 chunk（使用重写后的 query）
        List<SearchResult> rawDocs = retrieveRelevantDocs(rewrittenQuery);

        // 1.5 相关性门控：最高分 < 阈值 → 不把文档给 LLM
        RelevanceGate.GateDecision gate = relevanceGate.evaluate(rawDocs);
        List<SearchResult> effectiveDocs = gate.effectiveDocs();
        String gateReason = gate.passed() ? null : gate.reason();

        // 2. 缓存 key = 命名空间 + hash(query + docs 指纹 + session)
        String cacheKey = buildCacheKey(query, effectiveDocs.hashCode());

        // 3. 查 Redis 缓存（不可用时降级到本地缓存）
        //    注意：多轮对话的缓存 key 不变，但上下文不同 → 需要加入 session hash
        if (effectiveSid != null) {
            cacheKey = buildCacheKey(query + "|s:" + effectiveSid, effectiveDocs.hashCode());
        }
        CacheHitResult cached = getFromCache(cacheKey);
        if (cached != null) {
            cacheHits++;
            long total = cacheHits + cacheMisses;
            double hitRate = total > 0 ? (double) cacheHits / total * 100 : 0;
            log.info("缓存命中 | key={} | 总计 hits={} misses={} | 命中率={}%",
                    cacheKey, cacheHits, cacheMisses, String.format("%.1f", hitRate));
            String source = cached.fromRedis() ? "Redis缓存" : "本地缓存(降级)";
            long ageSec = (System.currentTimeMillis() - cached.cachedAt()) / 1000;
            return cached.value() + " (来自" + source + ", 已缓存" + ageSec + "秒)";
        }
        cacheMisses++;
        log.info("缓存未命中 | key={} | 总计 hits={} misses={}", cacheKey, cacheHits, cacheMisses);

        // 4. 构建 prompt（门控通过 → 带引用文档；未通过 → 告知 LLM 无相关信息）
        String prompt = buildPrompt(query, effectiveDocs, historyText, gateReason);

        // 5. 调用大模型（模拟）
        String response = llmClient.callLlm(prompt, "default");

        // 5.5 引用校验：检测 LLM 是否编造引用编号
        citationValidator.validate(response, effectiveDocs.size());

        // 6. 写入 Redis 缓存（带 TTL 抖动，不可用时降级到本地）
        putToCache(cacheKey, response);

        // 7. 保存本轮对话到会话上下文
        if (effectiveSid != null) {
            sessionService.appendMessage(effectiveSid, "user", query);
            sessionService.appendMessage(effectiveSid, "assistant", response);
        }

        return response + " (首次生成)";
    }

    /**
     * 带会话上下文的问答（供 SSE 流式端点调用）。
     * 与 ask() 逻辑相同，但不走结果缓存、不附加 "(首次生成)" 标签。
     */
    public String askWithContext(String query, String sessionId) {
        String effectiveSid = resolveSessionId(sessionId);
        String historyText = "";
        if (effectiveSid != null) {
            historyText = sessionService.formatHistory(effectiveSid);
        }

        String rewrittenQuery = queryRewriter.rewrite(query, historyText);
        List<SearchResult> rawDocs = retrieveRelevantDocs(rewrittenQuery);

        RelevanceGate.GateDecision gate = relevanceGate.evaluate(rawDocs);
        List<SearchResult> effectiveDocs = gate.effectiveDocs();
        String gateReason = gate.passed() ? null : gate.reason();

        String prompt = buildPrompt(query, effectiveDocs, historyText, gateReason);
        String response = llmClient.callLlm(prompt, "default");

        // 引用校验
        citationValidator.validate(response, effectiveDocs.size());

        if (effectiveSid != null) {
            sessionService.appendMessage(effectiveSid, "user", query);
            sessionService.appendMessage(effectiveSid, "assistant", response);
        }

        return response;
    }

    private String resolveSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        if (!sessionService.exists(sessionId)) {
            return sessionService.createSession();
        }
        return sessionId;
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

    /**
     * 混合检索 —— BM25 关键词 + Embedding 语义双路检索，RRF 融合。
     */
    private List<SearchResult> retrieveRelevantDocs(String query) {
        return hybridRetriever.retrieve(query, topK);
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

    /**
     * @param query        用户查询
     * @param docs         有效文档（门控通过 → 带分数的文档；门控未通过 → 空列表）
     * @param historyText  对话历史
     * @param noDocsReason 门控未通过原因（null = 正常 RAG 路径）
     */
    private String buildPrompt(String query, List<SearchResult> docs, String historyText, String noDocsReason) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个知识助手。请严格基于以下信息回答用户问题。\n\n");

        // 拼入多轮对话历史
        if (historyText != null && !historyText.isEmpty()) {
            sb.append(historyText);
        }

        // 门控未通过：告知 LLM 没有相关文档，禁止编造
        if (noDocsReason != null) {
            sb.append("=== 重要提示 ===\n");
            sb.append("参考文档中未找到与用户问题相关的信息（").append(noDocsReason).append("）。\n");
            sb.append("请如实告知用户这一情况，建议用户补充相关知识文档。\n");
            sb.append("禁止编造答案。禁止使用你训练数据中的知识。\n");
            sb.append("只说你确定能从参考文档中找到的信息。\n\n");
            sb.append("用户问题：「").append(query).append("」\n");
            sb.append("请用中文回答：");
            return sb.toString();
        }

        // 正常 RAG 路径
        if (docs.isEmpty()) {
            sb.append("用户问：「").append(query).append("」，但没有找到相关文档。请根据历史对话简要回答。");
            return sb.toString();
        }

        // 带编号的引用格式：[1] (来源: xx) 内容
        sb.append(citationFormatter.formatReferenceSection(docs));
        sb.append("\n");
        sb.append(citationFormatter.getCitationInstruction(docs.size()));
        sb.append("\n\n");
        sb.append("用户问题：「").append(query).append("」\n");
        sb.append("请用中文回答（必须引用来源编号）：");
        return sb.toString();
    }

}
