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

    @Value("${app.rag.relevance-threshold:0.35}")
    private double threshold;

    /**
     * 评估检索结果是否达到相关性门槛。
     */
    public GateDecision evaluate(List<SearchResult> docs) {
        if (docs == null || docs.isEmpty()) {
            log.debug("门控：检索无结果");
            return GateDecision.noDocs();
        }

        double maxScore = docs.stream()
                .mapToDouble(SearchResult::score)
                .max()
                .orElse(0.0);

        if (maxScore < threshold) {
            log.info("门控拦截：最高相关分数 {} < 阈值 {}，共 {} 条候选",
                    String.format("%.3f", maxScore), String.format("%.3f", threshold), docs.size());
            return GateDecision.belowThreshold(maxScore, threshold);
        }

        log.debug("门控通过：最高相关分数 {} >= 阈值 {}", String.format("%.3f", maxScore), String.format("%.3f", threshold));
        return GateDecision.passed(docs, maxScore);
    }

    // ==================== 门控决策 ====================

    public record GateDecision(
            boolean passed,
            double maxScore,
            List<SearchResult> effectiveDocs,
            String reason
    ) {
        private static final GateDecision NO_DOCS =
                new GateDecision(false, 0, List.of(), "未找到相关文档");

        public static GateDecision noDocs() {
            return NO_DOCS;
        }

        public static GateDecision belowThreshold(double maxScore, double threshold) {
            return new GateDecision(false, maxScore, List.of(),
                    String.format("相关度不足（最高分 %.2f，要求 %.2f）", maxScore, threshold));
        }

        public static GateDecision passed(List<SearchResult> docs, double maxScore) {
            return new GateDecision(true, maxScore, docs, "通过");
        }
    }
}
