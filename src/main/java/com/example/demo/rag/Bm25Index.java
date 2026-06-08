package com.example.demo.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * BM25 关键词检索引擎 —— 精确字面匹配，与 Embedding 语义检索互补。
 *
 * BM25 公式：
 *   score(q, d) = Σ IDF(qi) * tf(qi,d) * (k1+1) / (tf(qi,d) + k1*(1-b+b*|d|/avgdl))
 *
 * 相比 TF-IDF 的两个改进：
 *   1. 词频饱和：词出现 3 次和 10 次的信号差异不大（k1 控制）
 *   2. 文档长度归一化：长文档天然词多，需要惩罚（b 控制）
 *
 * 面试要点：
 *   "k1=1.2, b=0.75 是 TREC 评测中确定的最优参数组合，
 *    适用于绝大多数文本检索场景。"
 */
@Component
public class Bm25Index {

    private static final Logger log = LoggerFactory.getLogger(Bm25Index.class);

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    /** chunk ID → chunk 文本（Child 文本，用于检索） */
    private final Map<String, String> idToText = new LinkedHashMap<>();

    /** chunk ID → Parent ID（用于去重） */
    private final Map<String, String> idToParentId = new HashMap<>();

    /** chunk ID → Parent 文本（用于返回 LLM 的完整上下文） */
    private final Map<String, String> idToParentText = new HashMap<>();

    /** 倒排索引：term → (docId → termFreq) */
    private final Map<String, Map<String, Integer>> termToDocTf = new HashMap<>();

    /** 文档频率：term → 包含该 term 的文档数 */
    private final Map<String, Integer> docFrequency = new HashMap<>();

    /** 每个文档的 token 数量 */
    private final Map<String, Integer> docLength = new HashMap<>();

    /** 平均文档长度 */
    private double avgDocLen = 0;

    /** BM25 检索单条结果 */
    public record ScoredDoc(String id, String text, double bm25Score) {}

    // ==================== 索引构建 ====================

    /**
     * 从 chunk 映射构建 BM25 索引。
     * 调用时机：启动时全量加载，或文档更新后重建。
     */
    public void index(Map<String, String> documents) {
        clear();
        if (documents.isEmpty()) {
            log.warn("BM25 索引构建：输入文档为空");
            return;
        }

        idToText.putAll(documents);

        // 第一遍：分词 + 统计 TF + 文档长度
        for (var entry : documents.entrySet()) {
            String docId = entry.getKey();
            List<String> tokens = tokenize(entry.getValue());
            docLength.put(docId, tokens.size());

            Map<String, Integer> tf = new HashMap<>();
            for (String token : tokens) {
                tf.merge(token, 1, Integer::sum);
            }
            termToDocTf.put(docId, tf);

            // 统计 DF（每个 term 只计一次 per doc）
            for (String term : tf.keySet()) {
                docFrequency.merge(term, 1, Integer::sum);
            }
        }

        avgDocLen = docLength.values().stream()
                .mapToInt(Integer::intValue)
                .average()
                .orElse(1.0);

        log.info("BM25 索引构建完成：{} 个文档，{} 个唯一词项，平均文档长度 {} tokens",
                idToText.size(), docFrequency.size(), String.format("%.1f", avgDocLen));
    }

    /** 重建索引（文档更新时调用） */
    public void rebuild(Map<String, String> documents) {
        log.info("BM25 索引重建：清空旧索引，新文档数 {}", documents.size());
        index(documents);
    }

    /** 清空全部索引数据 */
    private void clear() {
        idToText.clear();
        idToParentId.clear();
        idToParentText.clear();
        termToDocTf.clear();
        docFrequency.clear();
        docLength.clear();
        avgDocLen = 0;
    }

    // ==================== 检索 ====================

    /**
     * BM25 检索 —— 返回按 BM25 得分降序的 Top-K 文档。
     *
     * IDF = log((N - n + 0.5) / (n + 0.5) + 1)
     * TF 分量 = tf * (k1+1) / (tf + k1 * (1-b + b * |d|/avgdl))
     */
    public List<ScoredDoc> search(String query, int topK) {
        if (idToText.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }

        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        List<ScoredDoc> results = new ArrayList<>();
        int N = idToText.size();

        for (var docEntry : idToText.entrySet()) {
            String docId = docEntry.getKey();
            double score = computeScore(docId, queryTerms, N);
            if (score > 0) {
                results.add(new ScoredDoc(docId, docEntry.getValue(), score));
            }
        }

        results.sort(Comparator.comparingDouble(ScoredDoc::bm25Score).reversed());
        return results.subList(0, Math.min(topK, results.size()));
    }

    private double computeScore(String docId, List<String> queryTerms, int N) {
        Map<String, Integer> tf = termToDocTf.get(docId);
        if (tf == null) return 0;

        int docLen = docLength.getOrDefault(docId, 0);
        if (docLen == 0 || avgDocLen <= 0) return 0;

        double score = 0;

        for (String term : queryTerms) {
            int df = docFrequency.getOrDefault(term, 0);
            if (df == 0) continue;

            int tfInDoc = tf.getOrDefault(term, 0);
            if (tfInDoc == 0) continue;

            // IDF: Robertson-Sparck Jones 平滑
            double idf = Math.log((N - df + 0.5) / (df + 0.5) + 1);

            // TF 归一化分量（含文档长度归一化）
            double tfNorm = (tfInDoc * (K1 + 1))
                    / (tfInDoc + K1 * (1 - B + B * docLen / avgDocLen));

            score += idf * tfNorm;
        }

        return score;
    }

    // ==================== 工具方法 ====================

    public int size() {
        return idToText.size();
    }

    public String getText(String id) {
        return idToText.get(id);
    }

    /** 设置 Parent ID 映射（层级 Chunk 模式下调用） */
    public void setParentIds(Map<String, String> parentIds) {
        this.idToParentId.clear();
        this.idToParentId.putAll(parentIds);
    }

    /** 设置 Parent 文本映射（层级 Chunk 模式下调用） */
    public void setParentTexts(Map<String, String> parentTexts) {
        this.idToParentText.clear();
        this.idToParentText.putAll(parentTexts);
    }

    /** 返回 Parent ID，若不存在则返回 chunk ID 自身 */
    public String getParentId(String id) {
        return idToParentId.getOrDefault(id, id);
    }

    /** 返回 Prompt 中使用的文本：优先 Parent 上下文，否则 Child 自身 */
    public String getTextForPrompt(String id) {
        String parent = idToParentText.get(id);
        return parent != null ? parent : idToText.get(id);
    }

    // ==================== 分词 ====================

    /**
     * 分词策略与 SimpleEmbeddingService 保持一致：
     * 按标点/空格切分 + 中文 2-gram 子词。
     */
    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        for (String word : text.toLowerCase()
                .split("[\\s，。；：、！？\\n,.;:!?()（）\\[\\]【】\"'\"\\-/]+")) {
            if (word.isEmpty()) continue;
            tokens.add(word);
            if (word.length() >= 2) {
                for (int i = 0; i <= word.length() - 2; i++) {
                    tokens.add(word.substring(i, i + 2));
                }
            }
        }
        return tokens;
    }
}
