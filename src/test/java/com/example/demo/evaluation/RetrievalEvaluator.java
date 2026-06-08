package com.example.demo.evaluation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索质量评测器 —— 计算 Recall@K / Precision@K / MRR / NDCG@K。
 *
 * 所有指标都是纯数学计算，不需要 LLM，秒级完成，可以频繁跑。
 *
 * 面试要点：
 * - Recall@K = 相关文档中有多少被检索到（RAG 最核心——漏了文档 LLM 就看不到）
 * - MRR = 第一个相关文档的排名倒数（衡量"关键文档是否排在前面"）
 * - NDCG@K = 考虑位置权重 + 多级相关度的排序质量（MRR 的升级版）
 */
public class RetrievalEvaluator {

    /**
     * 单条查询的检索指标。
     */
    public record RetrievalMetrics(
            double recallAtK,
            double precisionAtK,
            double mrr,
            double ndcgAtK,
            int relevantFound,
            int totalRelevant,
            int k
    ) {
        @Override
        public String toString() {
            return String.format(
                    "Recall@%d=%.2f Prec@%d=%.2f MRR=%.2f NDCG@%d=%.2f (found %d/%d)",
                    k, recallAtK, k, precisionAtK, mrr, k, ndcgAtK, relevantFound, totalRelevant);
        }
    }

    /** 批量评测结果 */
    public record BatchResult(
            List<String> evalIds,
            List<RetrievalMetrics> perQuery,
            double avgRecall,
            double avgPrecision,
            double avgMRR,
            double avgNDCG
    ) {
        public int totalQueries() { return evalIds.size(); }

        @Override
        public String toString() {
            return String.format(
                    "=== 检索评测报告 ===\n" +
                    "评测查询数: %d\n" +
                    "Avg Recall@K:  %.4f\n" +
                    "Avg Precision: %.4f\n" +
                    "Avg MRR:       %.4f\n" +
                    "Avg NDCG@K:    %.4f",
                    totalQueries(), avgRecall, avgPrecision, avgMRR, avgNDCG);
        }
    }

    /**
     * 对单条查询计算检索指标。
     *
     * @param retrievedDocIds  检索系统返回的 Top-K 文档 ID（按得分降序排列）
     * @param groundTruthPrefixes 真实相关文档的文件名前缀列表
     * @param k Top-K 参数
     */
    public RetrievalMetrics evaluate(
            List<String> retrievedDocIds,
            List<String> groundTruthPrefixes,
            int k) {

        List<String> topK = retrievedDocIds.subList(0, Math.min(k, retrievedDocIds.size()));

        // ---- Recall@K ----
        int relevantFound = 0;
        for (String prefix : groundTruthPrefixes) {
            if (EvalDataset.anyMatch(topK, prefix)) {
                relevantFound++;
            }
        }
        int totalRelevant = groundTruthPrefixes.size();
        double recallAtK = totalRelevant > 0 ? (double) relevantFound / totalRelevant : 1.0;

        // ---- Precision@K ----
        int relevantInTopK = 0;
        for (String docId : topK) {
            for (String prefix : groundTruthPrefixes) {
                if (EvalDataset.matchesGroundTruth(docId, prefix)) {
                    relevantInTopK++;
                    break;
                }
            }
        }
        double precisionAtK = topK.isEmpty() ? 0 : (double) relevantInTopK / topK.size();

        // ---- MRR (Mean Reciprocal Rank) ----
        double mrr = 0;
        for (String prefix : groundTruthPrefixes) {
            for (int i = 0; i < topK.size(); i++) {
                if (EvalDataset.matchesGroundTruth(topK.get(i), prefix)) {
                    mrr = Math.max(mrr, 1.0 / (i + 1)); // 取最高排名的那个
                    break;
                }
            }
        }

        // ---- NDCG@K (简化版：相关=1，不相关=0) ----
        double dcg = 0;
        for (int i = 0; i < topK.size(); i++) {
            boolean isRelevant = false;
            for (String prefix : groundTruthPrefixes) {
                if (EvalDataset.matchesGroundTruth(topK.get(i), prefix)) {
                    isRelevant = true;
                    break;
                }
            }
            if (isRelevant) {
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2)); // DCG = Σ rel_i / log2(i+1)
            }
        }
        // IDCG = 理想排序下前 totalRelevant 个位置都是相关的
        double idcg = 0;
        for (int i = 0; i < Math.min(totalRelevant, topK.size()); i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        double ndcgAtK = idcg > 0 ? dcg / idcg : 0;

        return new RetrievalMetrics(recallAtK, precisionAtK, mrr, ndcgAtK,
                relevantFound, totalRelevant, k);
    }

    /**
     * 对整个评测集批量评测。
     *
     * @param queries   评测查询列表
     * @param retriever 检索函数：query → 排序后的文档 ID 列表
     * @param k         Top-K 参数
     */
    public BatchResult evaluateBatch(
            List<EvalDataset.EvalQuery> queries,
            java.util.function.Function<String, List<String>> retriever,
            int k) {

        List<String> ids = new ArrayList<>();
        List<RetrievalMetrics> metrics = new ArrayList<>();

        double sumRecall = 0, sumPrecision = 0, sumMRR = 0, sumNDCG = 0;

        for (EvalDataset.EvalQuery q : queries) {
            List<String> retrieved = retriever.apply(q.query());
            RetrievalMetrics m = evaluate(retrieved, q.groundTruthDocPrefixes(), k);

            ids.add(q.id());
            metrics.add(m);

            sumRecall += m.recallAtK();
            sumPrecision += m.precisionAtK();
            sumMRR += m.mrr();
            sumNDCG += m.ndcgAtK();
        }

        int n = queries.size();
        return new BatchResult(ids, metrics,
                n > 0 ? sumRecall / n : 0,
                n > 0 ? sumPrecision / n : 0,
                n > 0 ? sumMRR / n : 0,
                n > 0 ? sumNDCG / n : 0);
    }

    /**
     * 打印每条查询的详细结果（用于排查低分查询）。
     */
    public static void printDetail(BatchResult result) {
        System.out.println(result);
        System.out.println("\n--- 逐条详情 ---");
        for (int i = 0; i < result.evalIds().size(); i++) {
            System.out.printf("  %s: %s%n", result.evalIds().get(i), result.perQuery().get(i));
        }
        System.out.println();
    }
}
