package com.example.demo.rag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * RRF（Reciprocal Rank Fusion）融合排序。
 *
 * 公式：score(d) = Σ 1 / (k + rank_i(d))
 *
 * 为什么不用加权求和？
 * - BM25 分数无上限，余弦相似度在 [-1,1]，量纲不同
 * - 归一化依赖当前检索结果分布，不稳定
 * - RRF 直接用排名，天然跨检索源可比，不需要归一化
 *
 * k=60 是论文经验值，防止排名第一的文档权重过大。
 */
@Component
public class RrfFusion {

    private static final double K = 60;

    /**
     * 融合两路排名，返回 Top-K。
     *
     * @param rankedA  检索路径 A 的 chunk ID 排名（最佳→最差）
     * @param rankedB  检索路径 B 的 chunk ID 排名（最佳→最差）
     * @param idToText chunk ID → 文本的映射
     * @param topK     最终返回数量
     */
    public List<SearchResult> fuse(
            List<String> rankedA,
            List<String> rankedB,
            Map<String, String> idToText,
            int topK) {

        Map<String, Double> rrfScores = new LinkedHashMap<>();

        // 路径 A 排名贡献
        for (int i = 0; i < rankedA.size(); i++) {
            rrfScores.merge(rankedA.get(i), 1.0 / (K + i + 1), Double::sum);
        }

        // 路径 B 排名贡献
        for (int i = 0; i < rankedB.size(); i++) {
            rrfScores.merge(rankedB.get(i), 1.0 / (K + i + 1), Double::sum);
        }

        // 按 RRF 总分降序，取 topK
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> new SearchResult(
                        e.getKey(),
                        idToText.getOrDefault(e.getKey(), ""),
                        e.getValue()))
                .toList();
    }
}
