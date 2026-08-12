package com.example.demo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 内存向量存储 —— ConcurrentHashMap + Redis JSON 可观测快照。
 *
 * O(N) 暴力余弦扫描，适合千级文档规模。
 * Redis 不是向量恢复源；重启时由文档入库管线重建内存索引。
 *
 * 面试直接说：
 *   "生产换 Redis Stack 的 HNSW 索引或 pgvector，
 *    InMemoryVectorStore 作为降级兜底——架构上 VectorStore 是接口，切换零业务代码改动。"
 */
@Component("inMemoryVectorStore")
public class InMemoryVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryVectorStore.class);
    private static final String REDIS_KEY = "rag:vectors";
    private final ObjectMapper mapper;
    private final StringRedisTemplate redisTemplate;

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    public InMemoryVectorStore(
            ObjectMapper mapper,
            StringRedisTemplate redisTemplate) {
        this.mapper = mapper;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void add(String id, String text, float[] embedding,
                    int dimension, String modelName,
                    String parentId, String parentText) {
        if (embedding == null || embedding.length == 0) {
            log.warn("忽略空向量 | id={} model={}", id, modelName);
            return;
        }
        Entry entry = new Entry(
                id,
                text,
                embedding,
                dimension,
                modelName,
                EmbeddingService.EmbeddingSource.UNKNOWN,
                parentId,
                parentText);
        storeEntry(entry);
    }

    @Override
    public void add(
            String id,
            String text,
            EmbeddingService.EmbeddingVector embedding,
            String parentId,
            String parentText) {
        if (embedding == null || embedding.isEmpty()) {
            log.warn("忽略空向量 | id={}", id);
            return;
        }
        storeEntry(new Entry(
                id,
                text,
                embedding.values(),
                embedding.dimension(),
                embedding.modelName(),
                embedding.source(),
                parentId,
                parentText));
    }

    private void storeEntry(Entry entry) {
        String id = entry.id();
        store.put(id, entry);
        try {
            StoredEntry se = new StoredEntry(
                    entry.id(),
                    entry.text(),
                    entry.embedding(),
                    entry.dimension(),
                    entry.modelName(),
                    entry.embeddingSource().value(),
                    entry.parentId(),
                    entry.parentText());
            redisTemplate.opsForHash().put(REDIS_KEY, id, mapper.writeValueAsString(se));
        } catch (JsonProcessingException e) {
            log.warn("Redis 向量序列化失败: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Redis 向量持久化失败 (Redis 不可用): {}", e.getMessage());
        }
    }

    @Override
    public List<Result> search(float[] queryEmbedding, int topK) {
        return searchInternal(queryEmbedding, topK, null);
    }

    @Override
    public List<Result> search(
            EmbeddingService.EmbeddingVector queryEmbedding,
            int topK) {
        if (queryEmbedding == null) {
            return List.of();
        }
        return searchInternal(
                queryEmbedding.values(),
                topK,
                queryEmbedding.source());
    }

    private List<Result> searchInternal(
            float[] queryEmbedding,
            int topK,
            EmbeddingService.EmbeddingSource requiredSource) {
        if (queryEmbedding == null
                || queryEmbedding.length == 0
                || topK <= 0) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>(store.values());
        List<Result> all = new ArrayList<>(entries.size());
        int incompatibleVectors = 0;

        for (Entry entry : entries) {
            if (requiredSource != null
                    && requiredSource != EmbeddingService.EmbeddingSource.UNKNOWN
                    && entry.embeddingSource()
                            != EmbeddingService.EmbeddingSource.UNKNOWN
                    && entry.embeddingSource() != requiredSource) {
                incompatibleVectors++;
                continue;
            }
            if (entry.embedding() == null
                    || entry.embedding().length != queryEmbedding.length) {
                incompatibleVectors++;
                continue;
            }
            double sim = cosine(queryEmbedding, entry.embedding());
            all.add(new Result(entry.id(), entry.text(), sim,
                    entry.parentId(), entry.parentText()));
        }
        if (incompatibleVectors > 0) {
            log.warn(
                    "跳过维度不兼容向量 | query_dimension={} skipped={}",
                    queryEmbedding.length,
                    incompatibleVectors);
        }

        all.sort(Comparator.comparingDouble(Result::score).reversed());
        return all.subList(0, Math.min(topK, all.size()));
    }

    @Override
    public void delete(String id) {
        store.remove(id);
        try {
            redisTemplate.opsForHash().delete(REDIS_KEY, id);
        } catch (Exception e) {
            log.warn("Redis 删除向量失败: {}", e.getMessage());
        }
    }

    @Override
    public int size() {
        return store.size();
    }

    @Override
    public Set<EmbeddingService.EmbeddingSource> embeddingSources() {
        Set<EmbeddingService.EmbeddingSource> sources = new HashSet<>();
        for (Entry entry : store.values()) {
            if (entry.embeddingSource()
                    != EmbeddingService.EmbeddingSource.UNKNOWN) {
                sources.add(entry.embeddingSource());
            }
        }
        return Set.copyOf(sources);
    }

    /**
     * 按模型删除全部向量 —— 模型迁移时使用。
     * 返回删除条数。
     */
    public int deleteByModel(String modelName) {
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Entry> e : store.entrySet()) {
            if (e.getValue().modelName().equals(modelName)) {
                toRemove.add(e.getKey());
            }
        }
        for (String id : toRemove) {
            store.remove(id);
            try {
                redisTemplate.opsForHash().delete(REDIS_KEY, id);
            } catch (Exception ex) {
                log.warn("Redis 删除向量失败: {}", ex.getMessage());
            }
        }
        log.info("已删除模型 [{}] 的 {} 条向量", modelName, toRemove.size());
        return toRemove.size();
    }

    // ==================== Parent 级去重 ====================

    /**
     * Parent 级去重：同一 Parent 只保留得分最高的 Child。
     * 去重后不够 topK 时从剩余候选中递补（保证多样性不缩水返回数量）。
     */
    private List<Result> deduplicateByParent(List<Result> sorted, int topK) {
        List<Result> deduped = new ArrayList<>();
        Set<String> seenParents = new HashSet<>();

        for (Result r : sorted) {
            String pid = r.parentId() != null ? r.parentId() : r.id();
            if (seenParents.add(pid)) {
                deduped.add(r);
                if (deduped.size() >= topK) break;
            }
        }

        if (deduped.size() < topK) {
            for (Result r : sorted) {
                String pid = r.parentId() != null ? r.parentId() : r.id();
                if (!seenParents.contains(pid)) {
                    seenParents.add(pid);
                    deduped.add(r);
                    if (deduped.size() >= topK) break;
                }
            }
        }

        return deduped;
    }

    // ==================== 余弦相似度 ====================

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null
                || a.length == 0
                || a.length != b.length) {
            return 0;
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** JSON 序列化载体 —— 仅作为可观测快照，不承诺启动恢复。 */
    private record StoredEntry(String id, String text, float[] embedding,
                               int dimension, String modelName,
                               String embeddingSource,
                               String parentId, String parentText) {}
}
