package com.example.demo.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.example.demo.service.EmbeddingService;
import com.example.demo.service.VectorStore;

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

    @Autowired
    private Bm25Index bm25Index;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private RrfFusion rrfFusion;

    @Autowired
    private Reranker reranker;

    /**
     * 两阶段混合检索入口。
     *
     * @param query 用户原始查询
     * @param topK  最终返回的文档数量
     * @return 精排后的 Top-K 结果
     */
    public List<SearchResult> retrieve(String query, int topK) {
        // 粗排取更多候选给 Reranker 留余量
        int candidateSize = topK * CANDIDATE_MULTIPLIER;

        // === 阶段一：并行双路粗排 ===
        CompletableFuture<List<String>> bm25Future = CompletableFuture.supplyAsync(
                () -> fetchBm25RankedIds(query, candidateSize));

        CompletableFuture<List<String>> vectorFuture = CompletableFuture.supplyAsync(
                () -> fetchVectorRankedIds(query, candidateSize));

        List<String> bm25RankedIds;
        List<String> vectorRankedIds;
        try {
            bm25RankedIds = bm25Future.get();
            vectorRankedIds = vectorFuture.get();
        } catch (Exception e) {
            log.error("混合检索并行执行异常: {}", e.getMessage(), e);
            bm25RankedIds = fetchBm25RankedIds(query, candidateSize);
            vectorRankedIds = List.of();
        }

        // 构建 Child text 映射（用于 RRF 融合，保持检索精度）
        Map<String, String> idToText = buildTextMap(bm25RankedIds, vectorRankedIds);

        // RRF 融合 → 候选集（使用 Child 文本）
        List<SearchResult> candidates = rrfFusion.fuse(
                bm25RankedIds, vectorRankedIds, idToText, candidateSize);

        // 补充 Parent 信息 + Parent 级去重
        candidates = enrichWithParentInfo(candidates);
        candidates = deduplicateByParent(candidates, candidateSize);

        log.debug("混合检索粗排：BM25 {} 条 + Embedding {} 条 → RRF 融合 {} 条候选（去重后 {} 条）",
                bm25RankedIds.size(), vectorRankedIds.size(),
                bm25RankedIds.size() + vectorRankedIds.size(), candidates.size());

        // === 阶段二：Cross-Encoder 精排（使用 Child 文本保证精度） ===
        try {
            List<SearchResult> reranked = reranker.rerank(query, candidates, topK);
            log.debug("Reranker 精排完成：{} 条候选 → {} 条最终结果",
                    candidates.size(), reranked.size());
            return reranked;
        } catch (Exception e) {
            log.warn("Reranker 精排失败，降级为粗排结果: {}", e.getMessage());
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }
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
            enriched.add(new SearchResult(r.id(), r.text(), r.score(), parentId, parentText));
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

    private List<String> fetchBm25RankedIds(String query, int topK) {
        return bm25Index.search(query, topK).stream()
                .map(Bm25Index.ScoredDoc::id)
                .toList();
    }

    private List<String> fetchVectorRankedIds(String query, int topK) {
        float[] queryVec = embeddingService.embed(query);
        if (queryVec.length == 0) {
            return List.of();
        }
        return vectorStore.search(queryVec, topK).stream()
                .map(VectorStore.Result::id)
                .toList();
    }

    /**
     * 从 BM25 和向量库结果中构建 ID → 文本 映射。
     * 不再对 query 做二次 embed，直接从已有结果中提取。
     */
    private Map<String, String> buildTextMap(List<String> bm25Ids, List<String> vectorIds) {
        Map<String, String> map = new HashMap<>();

        for (String id : bm25Ids) {
            String text = bm25Index.getTextForPrompt(id);
            if (text != null) {
                map.put(id, text);
            }
        }

        // 补充向量库中独有、BM25 没有的 ID
        for (String id : vectorIds) {
            if (!map.containsKey(id)) {
                String text = bm25Index.getTextForPrompt(id);
                if (text != null) {
                    map.put(id, text);
                }
            }
        }

        return map;
    }
}
