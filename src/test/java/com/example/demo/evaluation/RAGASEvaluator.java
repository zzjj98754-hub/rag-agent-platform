package com.example.demo.evaluation;

import java.util.ArrayList;
import java.util.List;

/**
 * RAGAS 风格生成质量评测器 —— 用 LLM-as-Judge 打分。
 *
 * 三项核心指标：
 *
 * Faithfulness（忠实度）：答案中的每句话是否能在检索文档中找到依据？
 *   高分 → LLM 没有编造，严格基于文档
 *   低分 → LLM 在幻觉，答案里有文档之外的信息
 *
 * Answer Relevance（答案相关性）：答案是否扣题？
 *   高分 → 答案精准回应用户问题
 *   低分 → 答非所问
 *
 * Context Relevance（上下文相关性）：检索文档是否与问题相关？
 *   高分 → 检索系统给了正确的文档
 *   低分 → 检索出了不相关的内容（噪音）
 *
 * 注意：本实现是简化版，用规则 + 关键词语义做近似评分。
 * 生产环境应使用 LLM-as-Judge（GPT-4/Claude）做精确评分。
 */
public class RAGASEvaluator {

    public record RAGASMetrics(
            double faithfulness,
            double answerRelevance,
            double contextRelevance
    ) {
        /** 综合得分 = 三项平均 */
        public double overall() {
            return (faithfulness + answerRelevance + contextRelevance) / 3.0;
        }

        @Override
        public String toString() {
            return String.format(
                    "Faithfulness=%.2f  AnswerRelevance=%.2f  ContextRelevance=%.2f  Overall=%.2f",
                    faithfulness, answerRelevance, contextRelevance, overall());
        }
    }

    /**
     * 对单次 RAG 结果打分。
     *
     * @param query     用户问题
     * @param answer    LLM 生成的答案
     * @param contexts  传给 LLM 的检索文档（有效文本）
     */
    public RAGASMetrics evaluate(String query, String answer, List<String> contexts) {
        String contextText = String.join(" ", contexts).toLowerCase();
        String answerLower = answer != null ? answer.toLowerCase() : "";
        String queryLower = query.toLowerCase();

        // ---- Faithfulness —— 答案中的关键词在上下文中出现比例 ----
        double faithfulness = computeFaithfulness(answerLower, contextText);

        // ---- Answer Relevance —— 答案和 query 的语义重叠度 ----
        double answerRelevance = computeAnswerRelevance(answerLower, queryLower);

        // ---- Context Relevance —— 检索文档和 query 的语义重叠度 ----
        double contextRelevance = computeContextRelevance(contextText, queryLower);

        return new RAGASMetrics(
                clamp(faithfulness),
                clamp(answerRelevance),
                clamp(contextRelevance));
    }

    /**
     * 批量评测。
     */
    public RAGASMetrics evaluateBatch(
            List<EvalDataset.EvalQuery> queries,
            java.util.function.Function<EvalDataset.EvalQuery, RAGResult> ragFunc) {

        List<RAGASMetrics> all = new ArrayList<>();
        for (EvalDataset.EvalQuery q : queries) {
            RAGResult ragResult = ragFunc.apply(q);
            if (ragResult == null) continue;
            all.add(evaluate(q.query(), ragResult.answer(), ragResult.contexts()));
        }

        if (all.isEmpty()) {
            return new RAGASMetrics(0, 0, 0);
        }

        double sumF = 0, sumA = 0, sumC = 0;
        for (RAGASMetrics m : all) {
            sumF += m.faithfulness();
            sumA += m.answerRelevance();
            sumC += m.contextRelevance();
        }
        int n = all.size();
        return new RAGASMetrics(sumF / n, sumA / n, sumC / n);
    }

    /** RAG 单次问答结果 */
    public record RAGResult(String answer, List<String> contexts) {}

    // ==================== 内部评分方法 ====================

    /**
     * 计算 Faithfulness —— 答案关键词在上下文中的覆盖率。
     *
     * 简化策略：提取答案中的名词/关键词，统计有多少能在上下文中找到。
     */
    private double computeFaithfulness(String answerLower, String contextText) {
        if (answerLower.isEmpty() || contextText.isEmpty()) return 0.5;

        // 提取答案中的关键词（按空格/标点分词）
        String[] answerWords = answerLower.split("[\\s，。；：、！？,.;:!?()（）]+");
        if (answerWords.length == 0) return 0.5;

        int found = 0;
        int meaningful = 0;
        for (String word : answerWords) {
            if (word.length() < 2) continue; // 跳过单字
            meaningful++;
            if (contextText.contains(word)) {
                found++;
            }
        }

        return meaningful > 0 ? (double) found / meaningful : 0.5;
    }

    /**
     * 计算 Answer Relevance —— 答案和 query 的词汇重叠度。
     */
    private double computeAnswerRelevance(String answerLower, String queryLower) {
        if (answerLower.isEmpty()) return 0;

        // 提取 query 中的关键词
        String[] queryKeywords = queryLower.split("[\\s，。；：、！？,.;:!?()（）]+");
        int found = 0;
        int meaningful = 0;
        for (String kw : queryKeywords) {
            if (kw.length() < 2) continue;
            meaningful++;
            if (answerLower.contains(kw)) {
                found++;
            }
        }

        return meaningful > 0 ? (double) found / meaningful : 0.5;
    }

    /**
     * 计算 Context Relevance —— 检索上下文和 query 的词汇重叠度。
     */
    private double computeContextRelevance(String contextText, String queryLower) {
        if (contextText.isEmpty()) return 0;

        String[] queryKeywords = queryLower.split("[\\s，。；：、！？,.;:!?()（）]+");
        int found = 0;
        int meaningful = 0;
        for (String kw : queryKeywords) {
            if (kw.length() < 2) continue;
            meaningful++;
            if (contextText.contains(kw)) {
                found++;
            }
        }

        return meaningful > 0 ? (double) found / meaningful : 0.5;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
