package com.example.demo.service;

import com.example.demo.observability.EmbeddingResilienceMetrics;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;

/**
 * 弹性 Embedding 服务 —— 主备切换 + 熔断器 + 查询缓存。
 *
 * 【业务问题】Embedding API 是外部服务，不可用时直接返回空向量会导致 RAG 链路完全断开。
 * 【技术方案】采用主备架构：SiliconFlow API 为主，TF-IDF 为备，中间夹一层熔断器 + 查询缓存。
 * 【实现原理】
 *   1. 查询缓存：SHA-256(text) → float[]，避免相同 query 重复调 API
 *   2. 熔断器：连续失败 N 次后打开，cooldown 期间直接走备选；超时后半开探测
 *   3. 自动降级：主服务不可用 → 无感切到备选 SimpleEmbeddingService
 * 【最终效果】API 故障时 RAG 链路不中断，查询性能提升（缓存命中跳过一次 API 调用）。
 */
@Component
@Primary
public class ResilientEmbeddingService implements EmbeddingService, EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(ResilientEmbeddingService.class);

    private final SiliconFlowEmbeddingService primary;
    private final SimpleEmbeddingService fallback;
    private final EmbeddingResilienceMetrics resilienceMetrics;
    private final ConcurrentHashMap<String, EmbeddingVector> cache =
            new ConcurrentHashMap<>();

    @Value("${app.embedding.cache-max-size}")
    private int cacheMaxSize;

    @Value("${app.embedding.circuit-threshold}")
    private int circuitThreshold;

    @Value("${app.embedding.circuit-cooldown-seconds}")
    private long circuitCooldownSeconds;

    // 熔断器状态
    private final AtomicReference<CircuitState> circuitState =
            new AtomicReference<>(CircuitState.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong circuitOpenedAt = new AtomicLong();
    private final AtomicBoolean halfOpenProbeInProgress = new AtomicBoolean();

    private enum CircuitState {
        CLOSED,    // 正常，请求走主服务
        OPEN,      // 熔断，请求直接走备选
        HALF_OPEN  // 半开，允许一次探测请求走主服务
    }

    public ResilientEmbeddingService(
            @Qualifier("siliconFlowEmbedding") SiliconFlowEmbeddingService primary,
            @Qualifier("simpleEmbedding") SimpleEmbeddingService fallback,
            EmbeddingResilienceMetrics resilienceMetrics) {
        this.primary = primary;
        this.fallback = fallback;
        this.resilienceMetrics = resilienceMetrics;
    }

    @Override
    public int dimension() {
        return primary.dimension();
    }

    @Override
    public String modelName() {
        if (circuitState.get() == CircuitState.CLOSED && primary.isAvailable()) {
            return primary.modelName();
        }
        return fallback.modelName() + " (降级)";
    }

    @Override
    public boolean isAvailable() {
        return true; // 本地 fallback 始终可用
    }

    @Override
    public void buildVocabulary(List<String> chunks) {
        fallback.buildVocabulary(chunks);
        cache.clear();
    }

    @Override
    public float[] embed(String text) {
        return embedWithMetadata(text).values();
    }

    @Override
    public float[] embed(Document document) {
        return embed(getEmbeddingContent(document));
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<float[]> vectors = embedBatch(request.getInstructions());
        List<Embedding> embeddings = new ArrayList<>(vectors.size());
        for (int index = 0; index < vectors.size(); index++) {
            embeddings.add(new Embedding(vectors.get(index), index));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public int dimensions() {
        return dimension();
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        return embedBatchWithMetadata(texts).stream()
                .map(EmbeddingVector::values)
                .toList();
    }

    @Override
    public EmbeddingVector embedWithMetadata(String text) {
        return embedWithMetadata(text, EmbeddingSource.UNKNOWN);
    }

    @Override
    public EmbeddingVector embedWithMetadata(
            String text,
            EmbeddingSource preferredSource) {
        EmbeddingSource preference = preferredSource == null
                ? EmbeddingSource.UNKNOWN
                : preferredSource;
        String cacheKey = cacheKey(text, preference);
        EmbeddingVector cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        EmbeddingVector result;
        if (preference == EmbeddingSource.LOCAL) {
            result = localEmbedding(text);
        } else {
            float[] primaryResult = tryPrimary(text);
            result = primaryResult == null || primaryResult.length == 0
                    ? localEmbedding(text)
                    : new EmbeddingVector(
                            primaryResult,
                            EmbeddingSource.SILICONFLOW,
                            primary.modelName());
        }
        cacheIfUsable(cacheKey, result);
        return result;
    }

    @Override
    public List<EmbeddingVector> embedBatchWithMetadata(
            List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }

        List<float[]> primaryResults = tryPrimaryBatch(texts);
        if (primaryResults.size() == texts.size()
                && primaryResults.stream().allMatch(vector -> vector.length > 0)) {
            List<EmbeddingVector> results = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                EmbeddingVector result = new EmbeddingVector(
                        primaryResults.get(i),
                        EmbeddingSource.SILICONFLOW,
                        primary.modelName());
                cacheIfUsable(cacheKey(texts.get(i), EmbeddingSource.UNKNOWN), result);
                results.add(result);
            }
            return results;
        }

        List<EmbeddingVector> results = new ArrayList<>(texts.size());
        for (String text : texts) {
            EmbeddingVector result = localEmbedding(text);
            cacheIfUsable(cacheKey(text, EmbeddingSource.UNKNOWN), result);
            cacheIfUsable(cacheKey(text, EmbeddingSource.LOCAL), result);
            results.add(result);
        }
        return results;
    }

    private EmbeddingVector localEmbedding(String text) {
        resilienceMetrics.recordFallbackCall();
        float[] values = fallback.embed(text);
        log.debug(
                "Embedding 降级到 Local TF-IDF: {}",
                text.substring(0, Math.min(50, text.length())));
        return new EmbeddingVector(
                values,
                EmbeddingSource.LOCAL,
                fallback.modelName());
    }

    private void cacheIfUsable(String key, EmbeddingVector result) {
        if (result == null || result.isEmpty()) {
            return;
        }
        evictIfNeeded();
        cache.put(key, result);
    }

    private String cacheKey(String text, EmbeddingSource preference) {
        return preference.name() + ':' + sha256Hex(text);
    }

    // ==================== 熔断器逻辑 ====================

    private float[] tryPrimary(String text) {
        if (!shouldTryPrimary()) {
            return null;
        }
        try {
            float[] result = primary.embed(text);
            if (result.length > 0) {
                onPrimarySuccess();
                return result;
            }
            onPrimaryFailure();
        } catch (Exception e) {
            log.warn("主 Embedding 服务异常: {}", e.getMessage());
            onPrimaryFailure();
        }
        return null;
    }

    private List<float[]> tryPrimaryBatch(List<String> texts) {
        if (!shouldTryPrimary()) {
            return List.of();
        }
        try {
            List<float[]> result = primary.embedBatch(texts);
            if (!result.isEmpty() && result.get(0).length > 0) {
                onPrimarySuccess();
                return result;
            }
            onPrimaryFailure();
        } catch (Exception e) {
            log.warn("主 Embedding 批量服务异常: {}", e.getMessage());
            onPrimaryFailure();
        }
        return List.of();
    }

    private boolean shouldTryPrimary() {
        switch (circuitState.get()) {
            case CLOSED:
                return true;
            case OPEN:
                if (System.currentTimeMillis() - circuitOpenedAt.get()
                        > circuitCooldownSeconds * 1000L
                        && circuitState.compareAndSet(
                                CircuitState.OPEN,
                                CircuitState.HALF_OPEN)) {
                    resilienceMetrics.setHalfOpen();
                    log.info("Embedding 熔断器进入半开状态，允许探测请求");
                    return halfOpenProbeInProgress.compareAndSet(false, true);
                }
                return false;
            case HALF_OPEN:
                return halfOpenProbeInProgress.compareAndSet(false, true);
            default:
                return true;
        }
    }

    private void onPrimarySuccess() {
        consecutiveFailures.set(0);
        if (circuitState.compareAndSet(
                CircuitState.HALF_OPEN,
                CircuitState.CLOSED)) {
            halfOpenProbeInProgress.set(false);
            resilienceMetrics.setClosed();
            primary.markAvailable();
            log.info("Embedding 熔断器关闭，主服务恢复");
        }
    }

    private void onPrimaryFailure() {
        resilienceMetrics.recordPrimaryFailure();
        int failures = consecutiveFailures.incrementAndGet();
        CircuitState currentState = circuitState.get();
        if (currentState == CircuitState.HALF_OPEN
                && circuitState.compareAndSet(
                        CircuitState.HALF_OPEN,
                        CircuitState.OPEN)) {
            // 半开状态探测失败，重新打开熔断器
            circuitOpenedAt.set(System.currentTimeMillis());
            halfOpenProbeInProgress.set(false);
            primary.markUnavailable();
            resilienceMetrics.setOpen();
            resilienceMetrics.recordCircuitOpen();
            log.warn("Embedding 半开探测失败，熔断器重新打开");
        } else if (currentState == CircuitState.CLOSED
                && failures >= circuitThreshold
                && circuitState.compareAndSet(
                        CircuitState.CLOSED,
                        CircuitState.OPEN)) {
            circuitOpenedAt.set(System.currentTimeMillis());
            primary.markUnavailable();
            resilienceMetrics.setOpen();
            resilienceMetrics.recordCircuitOpen();
            log.warn("Embedding 熔断器打开（连续失败 {} 次），cooldown {}s",
                    failures, circuitCooldownSeconds);
        }
    }

    // ==================== 缓存管理 ====================

    private void evictIfNeeded() {
        if (cache.size() >= cacheMaxSize) {
            // 简单 FIFO 淘汰：清除约 10% 的旧条目
            int toRemove = Math.max(1, cacheMaxSize / 10);
            var it = cache.keySet().iterator();
            for (int i = 0; i < toRemove && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
            log.debug("Embedding 缓存淘汰 {} 条，当前大小 {}", toRemove, cache.size());
        }
    }

    // ==================== 监控指标 ====================

    public int cacheSize() {
        return cache.size();
    }

    public String circuitState() {
        return circuitState.get().name();
    }

    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    // ==================== 工具方法 ====================

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
