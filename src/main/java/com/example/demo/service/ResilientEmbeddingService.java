package com.example.demo.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
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
public class ResilientEmbeddingService implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(ResilientEmbeddingService.class);

    private final SiliconFlowEmbeddingService primary;
    private final SimpleEmbeddingService fallback;
    private final ConcurrentHashMap<String, float[]> cache = new ConcurrentHashMap<>();

    @Value("${app.embedding.cache-max-size:2000}")
    private int cacheMaxSize;

    @Value("${app.embedding.circuit-threshold:3}")
    private int circuitThreshold;

    @Value("${app.embedding.circuit-cooldown-seconds:60}")
    private long circuitCooldownSeconds;

    // 熔断器状态
    private volatile CircuitState circuitState = CircuitState.CLOSED;
    private volatile int consecutiveFailures = 0;
    private volatile long circuitOpenedAt = 0;

    private enum CircuitState {
        CLOSED,    // 正常，请求走主服务
        OPEN,      // 熔断，请求直接走备选
        HALF_OPEN  // 半开，允许一次探测请求走主服务
    }

    public ResilientEmbeddingService(
            @Qualifier("siliconFlowEmbedding") SiliconFlowEmbeddingService primary,
            @Qualifier("simpleEmbedding") SimpleEmbeddingService fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public int dimension() {
        return primary.dimension();
    }

    @Override
    public String modelName() {
        if (circuitState == CircuitState.CLOSED && primary.isAvailable()) {
            return primary.modelName();
        }
        return fallback.modelName() + " (降级)";
    }

    @Override
    public boolean isAvailable() {
        return primary.isAvailable() || true; // fallback 始终可用
    }

    @Override
    public void buildVocabulary(List<String> chunks) {
        fallback.buildVocabulary(chunks);
    }

    @Override
    public float[] embed(String text) {
        // 1. 查询缓存
        String cacheKey = sha256Hex(text);
        float[] cached = cache.get(cacheKey);
        if (cached != null) {
            log.debug("Embedding 缓存命中: {}", cacheKey.substring(0, 8));
            return cached;
        }

        // 2. 尝试主服务
        float[] result = tryPrimary(text);

        // 3. 主服务不可用 → 降级到备选
        if (result == null || result.length == 0) {
            log.debug("Embedding 降级到 TF-IDF: {}", text.substring(0, Math.min(50, text.length())));
            result = fallback.embed(text);
        }

        // 4. 写入缓存
        if (result != null && result.length > 0) {
            evictIfNeeded();
            cache.put(cacheKey, result);
        }

        return result != null ? result : new float[0];
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> results = new ArrayList<>();
        List<Integer> missIndexes = new ArrayList<>();

        // 1. 查询缓存
        for (int i = 0; i < texts.size(); i++) {
            String cacheKey = sha256Hex(texts.get(i));
            float[] cached = cache.get(cacheKey);
            if (cached != null) {
                results.add(cached);
            } else {
                results.add(null); // 占位
                missIndexes.add(i);
            }
        }

        if (missIndexes.isEmpty()) {
            return results;
        }

        // 2. 收集未命中文本
        List<String> missedTexts = new ArrayList<>();
        for (int idx : missIndexes) {
            missedTexts.add(texts.get(idx));
        }

        // 3. 尝试主服务批量 embed
        List<float[]> batchResult = tryPrimaryBatch(missedTexts);

        // 4. 降级：逐条走 fallback
        if (batchResult.isEmpty()) {
            batchResult = new ArrayList<>();
            for (String text : missedTexts) {
                float[] vec = fallback.embed(text);
                batchResult.add(vec.length > 0 ? vec : new float[0]);
            }
        }

        // 5. 填充结果并写入缓存
        for (int i = 0; i < missIndexes.size(); i++) {
            int origIdx = missIndexes.get(i);
            float[] vec = i < batchResult.size() ? batchResult.get(i) : new float[0];
            results.set(origIdx, vec);

            if (vec.length > 0) {
                String cacheKey = sha256Hex(texts.get(origIdx));
                evictIfNeeded();
                cache.put(cacheKey, vec);
            }
        }

        return results;
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
        switch (circuitState) {
            case CLOSED:
                return true;
            case OPEN:
                if (System.currentTimeMillis() - circuitOpenedAt > circuitCooldownSeconds * 1000L) {
                    circuitState = CircuitState.HALF_OPEN;
                    log.info("Embedding 熔断器进入半开状态，允许探测请求");
                    return true;
                }
                return false;
            case HALF_OPEN:
                return true;
            default:
                return true;
        }
    }

    private void onPrimarySuccess() {
        consecutiveFailures = 0;
        if (circuitState == CircuitState.HALF_OPEN) {
            circuitState = CircuitState.CLOSED;
            primary.markAvailable();
            log.info("Embedding 熔断器关闭，主服务恢复");
        }
    }

    private void onPrimaryFailure() {
        consecutiveFailures++;
        if (circuitState == CircuitState.HALF_OPEN) {
            // 半开状态探测失败，重新打开熔断器
            circuitState = CircuitState.OPEN;
            circuitOpenedAt = System.currentTimeMillis();
            primary.markUnavailable();
            log.warn("Embedding 半开探测失败，熔断器重新打开");
        } else if (consecutiveFailures >= circuitThreshold) {
            circuitState = CircuitState.OPEN;
            circuitOpenedAt = System.currentTimeMillis();
            primary.markUnavailable();
            log.warn("Embedding 熔断器打开（连续失败 {} 次），cooldown {}s",
                    consecutiveFailures, circuitCooldownSeconds);
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

    public CircuitState circuitState() {
        return circuitState;
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
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
