package com.example.demo.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Real Redis Stack HNSW adapter with a bounded availability fallback. */
@Component("redisStackVectorStore")
public class RedisStackVectorStore implements VectorStore {
    private static final Logger log = LoggerFactory.getLogger(RedisStackVectorStore.class);

    private final org.springframework.ai.vectorstore.VectorStore delegate;
    private final StringRedisTemplate redis;
    private final String idsKey;
    private final String sourcesKey;
    private final Map<String, Entry> fallback = new ConcurrentHashMap<>();

    public RedisStackVectorStore(
            ObjectProvider<org.springframework.ai.vectorstore.VectorStore> delegates,
            StringRedisTemplate redis,
            @Value("${app.vector-store.index-name}") String indexName) {
        this.delegate = delegates.getIfAvailable();
        this.redis = redis;
        this.idsKey = "rag:vector:ids:" + indexName;
        this.sourcesKey = "rag:vector:sources:" + indexName;
    }

    @Override
    public void add(String id, String text, float[] embedding, int dimension,
            String modelName, String parentId, String parentText) {
        add(id, text, new EmbeddingService.EmbeddingVector(
                embedding, EmbeddingService.EmbeddingSource.UNKNOWN, modelName),
                parentId, parentText);
    }

    @Override
    public void add(String id, String text, EmbeddingService.EmbeddingVector embedding,
            String parentId, String parentText) {
        if (embedding == null || embedding.isEmpty()) return;
        Entry entry = new Entry(id, text, embedding.values(), embedding.dimension(),
                embedding.modelName(), embedding.source(), parentId, parentText);
        fallback.put(id, entry);
        if (delegate == null) return;
        try {
            Map<String, Object> metadata = new java.util.LinkedHashMap<>();
            metadata.put("embeddingSource", embedding.source().name());
            metadata.put("modelName", embedding.modelName());
            metadata.put("dimension", embedding.dimension());
            metadata.put("parentId", parentId == null ? id : parentId);
            metadata.put("parentText", parentText == null ? text : parentText);
            delegate.add(List.of(new Document(id, text, metadata)));
            redis.opsForSet().add(idsKey, id);
            redis.opsForSet().add(sourcesKey, embedding.source().name());
        } catch (RuntimeException ex) {
            log.warn("Redis HNSW add failed; local fallback retained | id={} error={}",
                    id, ex.getMessage());
        }
    }

    @Override
    public List<Result> search(float[] queryEmbedding, int topK) {
        return localSearch(queryEmbedding, topK, null);
    }

    @Override
    public List<Result> search(EmbeddingService.EmbeddingVector queryEmbedding, int topK) {
        return queryEmbedding == null ? List.of()
                : localSearch(queryEmbedding.values(), topK, queryEmbedding.source());
    }

    @Override
    public List<Result> search(String queryText,
            EmbeddingService.EmbeddingVector queryEmbedding, int topK) {
        if (delegate == null || queryText == null || queryText.isBlank()) {
            return search(queryEmbedding, topK);
        }
        try {
            String filter = queryEmbedding == null
                    || queryEmbedding.source() == EmbeddingService.EmbeddingSource.UNKNOWN
                    ? null
                    : "embeddingSource == '" + queryEmbedding.source().name() + "'";
            SearchRequest.Builder request = SearchRequest.builder()
                    .query(queryText)
                    .topK(Math.max(topK * 3, topK))
                    .similarityThresholdAll();
            if (filter != null) request.filterExpression(filter);
            return deduplicate(delegate.similaritySearch(request.build()).stream()
                    .map(this::toResult)
                    .sorted(Comparator.comparingDouble(Result::score).reversed())
                    .toList(), topK);
        } catch (RuntimeException ex) {
            log.warn("Redis HNSW search failed; using local fallback: {}", ex.getMessage());
            return search(queryEmbedding, topK);
        }
    }

    private Result toResult(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new Result(
                document.getId(), document.getText(),
                document.getScore() == null ? 0 : document.getScore(),
                string(metadata.get("parentId")),
                string(metadata.get("parentText")));
    }

    private String string(Object value) { return value == null ? null : value.toString(); }

    private List<Result> localSearch(float[] query, int topK,
            EmbeddingService.EmbeddingSource source) {
        if (query == null || query.length == 0 || topK <= 0) return List.of();
        List<Result> values = new ArrayList<>();
        for (Entry entry : fallback.values()) {
            if (entry.embedding().length != query.length) continue;
            if (source != null && source != EmbeddingService.EmbeddingSource.UNKNOWN
                    && entry.embeddingSource() != EmbeddingService.EmbeddingSource.UNKNOWN
                    && source != entry.embeddingSource()) continue;
            values.add(new Result(entry.id(), entry.text(),
                    cosine(query, entry.embedding()), entry.parentId(), entry.parentText()));
        }
        values.sort(Comparator.comparingDouble(Result::score).reversed());
        return deduplicate(values, topK);
    }

    private List<Result> deduplicate(List<Result> sorted, int topK) {
        List<Result> result = new ArrayList<>();
        Set<String> parents = new HashSet<>();
        for (Result item : sorted) {
            if (parents.add(item.parentId() == null ? item.id() : item.parentId())) {
                result.add(item);
                if (result.size() == topK) break;
            }
        }
        return List.copyOf(result);
    }

    @Override
    public void delete(String id) {
        fallback.remove(id);
        if (delegate == null) return;
        try {
            delegate.delete(List.of(id));
            redis.opsForSet().remove(idsKey, id);
        } catch (RuntimeException ex) {
            log.warn("Redis HNSW delete failed | id={} error={}", id, ex.getMessage());
        }
    }

    @Override
    public int size() {
        try {
            Long size = redis.opsForSet().size(idsKey);
            return size == null ? fallback.size() : Math.toIntExact(size);
        } catch (RuntimeException ex) {
            return fallback.size();
        }
    }

    @Override
    public Set<EmbeddingService.EmbeddingSource> embeddingSources() {
        try {
            Set<String> values = redis.opsForSet().members(sourcesKey);
            if (values == null) return Set.of();
            return values.stream().map(EmbeddingService.EmbeddingSource::valueOf)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        } catch (RuntimeException ex) {
            return fallback.values().stream().map(Entry::embeddingSource)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    private double cosine(float[] left, float[] right) {
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        return leftNorm == 0 || rightNorm == 0 ? 0
                : dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
