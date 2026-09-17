package com.example.demo.rag;

import com.example.demo.observability.RagObservability;
import com.example.demo.observability.RagRequestObservation;
import com.example.demo.observability.RagStage;
import com.example.demo.service.EmbeddingService;
import com.example.demo.service.VectorStore;
import com.example.demo.service.ElasticsearchChunkIndexer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 混合检索编排器 —— 粗排 + 精排两阶段检索。
 *
 * 阶段一（粗排）：BM25 + Embedding 双路并行 → RRF 融合 → Top-K×6 候选
 * 阶段二（精排）：Cross-Encoder Reranker 对候选逐条精细打分 → 最终 Top-K
 *
 * 设计原则：
 * - 不负责具体检索算法，只负责「调度双路 → 融合 → Rerank → 返回」
 * - CompletableFuture 并行执行，总延迟 = max(两路延迟)，不是 sum
 * - Reranker 失败时降级为直接使用粗排结果，不阻塞链路
 */
@Component
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);
    private static final int CANDIDATE_MULTIPLIER = 6;

    private final Bm25Index bm25Index;
    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final RrfFusion rrfFusion;
    private final Reranker reranker;
    private final Executor retrievalExecutor;
    private final RagObservability observability;
    private final ElasticsearchChunkIndexer elasticsearch;
    private final boolean elasticsearchEnabled;

    @org.springframework.beans.factory.annotation.Autowired
    public HybridRetriever(
            Bm25Index bm25Index,
            EmbeddingService embeddingService,
            VectorStore vectorStore,
            RrfFusion rrfFusion,
            Reranker reranker,
            @Qualifier("ragRetrievalExecutor") Executor retrievalExecutor,
            RagObservability observability,
            ElasticsearchChunkIndexer elasticsearch,
            @org.springframework.beans.factory.annotation.Value("${app.index.elasticsearch.enabled}") boolean elasticsearchEnabled,
            @org.springframework.beans.factory.annotation.Value("${app.vector-store.backend}") String vectorBackend) {
        this.bm25Index = bm25Index;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.rrfFusion = rrfFusion;
        this.reranker = reranker;
        this.retrievalExecutor = retrievalExecutor;
        this.observability = observability;
        this.elasticsearch = elasticsearch;
        this.elasticsearchEnabled = elasticsearchEnabled || "elasticsearch".equalsIgnoreCase(vectorBackend);
    }

    /** Compatibility constructor for retrieval-focused tests. */
    public HybridRetriever(Bm25Index bm25Index, EmbeddingService embeddingService, VectorStore vectorStore,
            RrfFusion rrfFusion, Reranker reranker, Executor retrievalExecutor, RagObservability observability) {
        this(bm25Index, embeddingService, vectorStore, rrfFusion, reranker, retrievalExecutor, observability, null, false, "in-memory");
    }

    /**
     * 两阶段混合检索入口。
     *
     * @param query 用户原始查询
     * @param topK  最终返回的文档数量
     * @return 精排后的 Top-K 结果
     */
    public List<SearchResult> retrieve(String query, int topK) {
        return retrieve(query, topK, Set.of());
    }

    /**
     * 在保持既有 BM25 + Vector + RRF + Rerank 链路的前提下，限制候选资料来源。
     * allowedSources 为空表示不做范围限制；非空时只保留 chunk id 对应的文件名。
     */
    public List<SearchResult> retrieve(
            String query,
            int topK,
            Set<String> allowedSources) {
        Set<String> sourceFilter = allowedSources == null
                ? Set.of()
                : allowedSources;
        RagRequestObservation observation = observability.currentObservation();
        long retrievalStart = System.nanoTime();
        // 粗排取更多候选给 Reranker 留余量
        int candidateSize = topK * CANDIDATE_MULTIPLIER;

        // === 阶段一：并行双路粗排 ===
        CompletableFuture<List<String>> bm25Future = CompletableFuture.supplyAsync(
                () -> fetchBm25RankedIds(query, candidateSize, observation, sourceFilter),
                retrievalExecutor);

        CompletableFuture<List<String>> vectorFuture = CompletableFuture.supplyAsync(
                () -> fetchVectorRankedIds(query, candidateSize, observation, sourceFilter),
                retrievalExecutor);

        List<String> bm25RankedIds;
        List<String> vectorRankedIds;
        try {
            bm25RankedIds = bm25Future.get();
            vectorRankedIds = vectorFuture.get();
        } catch (Exception e) {
            log.error("混合检索并行执行异常: {}", e.getMessage(), e);
            bm25RankedIds = fetchBm25RankedIds(query, candidateSize, observation, sourceFilter);
            vectorRankedIds = List.of();
        }

        // 构建 Child text 映射（用于 RRF 融合，保持检索精度）
        Map<String, String> idToText = buildTextMap(bm25RankedIds, vectorRankedIds);

        // RRF 融合 → 候选集（使用 Child 文本）
        List<SearchResult> candidates = rrfFusion.fuse(
                bm25RankedIds, vectorRankedIds, idToText, candidateSize);

        log.debug("混合检索粗排：BM25 {} 条 + Embedding {} 条 → RRF 融合 {} 条 Child 候选",
                bm25RankedIds.size(), vectorRankedIds.size(),
                candidates.size());
        observability.recordDuration(
                observation,
                RagStage.RETRIEVAL,
                System.nanoTime() - retrievalStart);

        // === 阶段二：Cross-Encoder 精排（使用 Child 文本保证精度） ===
        List<SearchResult> rerankCandidates = candidates;
        List<SearchResult> rankedChildren;
        try {
            rankedChildren = observability.measure(
                    observation,
                    RagStage.RERANK,
                    () -> reranker.rerank(
                            query,
                            rerankCandidates,
                            candidateSize));
            log.debug("Reranker 精排完成：{} 条候选 → {} 条最终结果",
                    candidates.size(), rankedChildren.size());
        } catch (Exception e) {
            log.warn("Reranker 精排失败，降级为粗排结果: {}", e.getMessage());
            rankedChildren = candidates;
        }

        // Small-to-Big 只发生在精排之后：召回、融合、精排均使用 Child，
        // 最终进入 Prompt 前才展开 Parent 并按 Parent 去重。
        return deduplicateByParent(
                enrichWithParentInfo(rankedChildren),
                topK);
    }

    // ==================== 内部方法 ====================

    /**
     * 补充 Parent 信息：从 BM25 索引中获取每个 Child 的 Parent ID 和 Parent 文本。
     * BM25 索引在 ChatService.initVectorStore() 中已设置 Parent 映射。
     */
    private List<SearchResult> enrichWithParentInfo(List<SearchResult> candidates) {
        List<SearchResult> enriched = new ArrayList<>();
        for (SearchResult r : candidates) {
            String parentId = bm25Index.getParentId(r.id());
            String parentText = bm25Index.getTextForPrompt(r.id());
            enriched.add(r.withParent(parentId, parentText));
        }
        return enriched;
    }

    /**
     * Parent 级去重：同一 Parent 只保留得分最高的 Child。
     * 去重后不够 topK 时从剩余候选中递补。
     */
    private List<SearchResult> deduplicateByParent(List<SearchResult> sorted, int topK) {
        List<SearchResult> deduped = new ArrayList<>();
        Set<String> seenParents = new HashSet<>();

        for (SearchResult r : sorted) {
            String pid = r.parentId() != null ? r.parentId() : r.id();
            if (seenParents.add(pid)) {
                deduped.add(r);
                if (deduped.size() >= topK) break;
            }
        }

        if (deduped.size() < topK) {
            for (SearchResult r : sorted) {
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

    private List<String> fetchBm25RankedIds(
            String query,
            int topK,
            RagRequestObservation observation,
            Set<String> allowedSources) {
        List<String> ids = observability.measure(
                observation,
                RagStage.BM25,
                () -> elasticsearchEnabled && elasticsearch != null
                        ? elasticsearch.searchText(query, topK)
                        : bm25Index.search(query, Math.max(topK, bm25Index.size()))
                                .stream().map(Bm25Index.ScoredDoc::id).toList());
        return filterSources(ids, allowedSources, topK);
    }

    private List<String> fetchVectorRankedIds(
            String query,
            int topK,
            RagRequestObservation observation,
            Set<String> allowedSources) {
        Set<EmbeddingService.EmbeddingSource> sources =
                vectorStore.embeddingSources();
        EmbeddingService.EmbeddingSource preferredSource = sources.size() == 1
                ? sources.iterator().next()
                : EmbeddingService.EmbeddingSource.UNKNOWN;
        EmbeddingService.EmbeddingVector queryVec = observability.measure(
                observation,
                RagStage.EMBEDDING,
                () -> embeddingService.embedWithMetadata(
                        query,
                        preferredSource));
        if (queryVec.isEmpty()) {
            return List.of();
        }
        return vectorStore.search(query, queryVec, Math.max(topK, vectorStore.size())).stream()
                .map(VectorStore.Result::id)
                .filter(id -> allowedSources.isEmpty() || allowedSources.contains(sourceOf(id)))
                .limit(topK)
                .toList();
    }

    private List<String> filterSources(
            List<String> ids,
            Set<String> allowedSources,
            int topK) {
        return ids.stream()
                .filter(id -> allowedSources.isEmpty() || allowedSources.contains(sourceOf(id)))
                .limit(topK)
                .toList();
    }

    private String sourceOf(String chunkId) {
        if (chunkId == null) return "";
        int separator = chunkId.indexOf(':');
        return separator > 0 ? chunkId.substring(0, separator) : chunkId;
    }

    /**
     * 从 BM25 和向量库结果中构建 ID → 文本 映射。
     * 不再对 query 做二次 embed，直接从已有结果中提取。
     */
    private Map<String, String> buildTextMap(List<String> bm25Ids, List<String> vectorIds) {
        Map<String, String> map = new HashMap<>();

        for (String id : bm25Ids) {
            String text = bm25Index.getText(id);
            if (text == null && elasticsearchEnabled && elasticsearch != null) text = elasticsearch.text(id);
            if (text != null) {
                map.put(id, text);
            }
        }

        // 补充向量库中独有、BM25 没有的 ID
        for (String id : vectorIds) {
            if (!map.containsKey(id)) {
                String text = bm25Index.getText(id);
                if (text == null && elasticsearchEnabled && elasticsearch != null) text = elasticsearch.text(id);
                if (text != null) {
                    map.put(id, text);
                }
            }
        }

        return map;
    }
}
