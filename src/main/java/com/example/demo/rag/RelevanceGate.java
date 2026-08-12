package com.example.demo.rag;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 相关性门控 —— 在 Reranker 精排后检查最高分数是否达标。
 *
 * 核心原则：不给 LLM 不相关的文档，不相关文档是 RAG 幻觉的最大来源。
 *
 * Reranker 分数 < 阈值 → 不走 RAG 路径 → Prompt 明确告知 LLM「未找到相关信息」。
 * Reranker 分数 >= 阈值 → 正常 RAG 路径 → 文档拼入 Prompt。
 *
 * 面试要点：
 * "阈值不是拍脑袋定的——在评测集上做阈值扫描，选 F1 最高的点。
 *  冷启动建议从 0.35 开始，后续根据用户反馈调整。"
 */
@Component
public class RelevanceGate {

    private static final Logger log = LoggerFactory.getLogger(RelevanceGate.class);

    private final double bgeThreshold;
    private final double rrfThreshold;

    public RelevanceGate(
            @Value("${app.rag.relevance-thresholds.bge}")
                    double bgeThreshold,
            @Value("${app.rag.relevance-thresholds.rrf}")
                    double rrfThreshold) {
        this.bgeThreshold = bgeThreshold;
        this.rrfThreshold = rrfThreshold;
    }

    /**
     * 评估检索结果是否达到相关性门槛。
     */
    public GateDecision evaluate(List<SearchResult> docs) {
        if (docs == null || docs.isEmpty()) {
            log.debug("门控：检索无结果");
            return GateDecision.noDocs();
        }

        RerankResult.ScoreType scoreType = docs.get(0).scoreType();
        boolean mixedScoreTypes = docs.stream()
                .anyMatch(document -> document.scoreType() != scoreType);
        if (mixedScoreTypes) {
            throw new IllegalStateException(
                    "检索结果混用了 BGE 与 RRF 评分体系");
        }
        double maxScore = docs.stream()
                .mapToDouble(SearchResult::score)
                .max()
                .orElse(0.0);
        double threshold = scoreType == RerankResult.ScoreType.BGE
                ? bgeThreshold
                : rrfThreshold;

        if (maxScore < threshold) {
            log.info("门控拦截：scoreType={} 最高相关分数 {} < 阈值 {}，共 {} 条候选",
                    scoreType, String.format("%.3f", maxScore),
                    String.format("%.3f", threshold), docs.size());
            return GateDecision.belowThreshold(
                    maxScore, threshold, scoreType);
        }

        log.debug("门控通过：scoreType={} 最高相关分数 {} >= 阈值 {}",
                scoreType, String.format("%.3f", maxScore),
                String.format("%.3f", threshold));
        return GateDecision.passed(docs, maxScore, scoreType);
    }

    // ==================== 门控决策 ====================

    public record GateDecision(
            boolean passed,
            double maxScore,
            RerankResult.ScoreType scoreType,
            List<SearchResult> effectiveDocs,
            String reason
    ) {
        private static final GateDecision NO_DOCS =
                new GateDecision(false, 0, RerankResult.ScoreType.RRF,
                        List.of(), "未找到相关文档");

        public static GateDecision noDocs() {
            return NO_DOCS;
        }

        public static GateDecision belowThreshold(
                double maxScore,
                double threshold,
                RerankResult.ScoreType scoreType) {
            return new GateDecision(false, maxScore, scoreType, List.of(),
                    String.format("相关度不足（最高分 %.2f，要求 %.2f）", maxScore, threshold));
        }

        public static GateDecision passed(
                List<SearchResult> docs,
                double maxScore,
                RerankResult.ScoreType scoreType) {
            return new GateDecision(true, maxScore, scoreType, docs, "通过");
        }
    }
}
