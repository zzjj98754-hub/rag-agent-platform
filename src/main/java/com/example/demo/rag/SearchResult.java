package com.example.demo.rag;

/**
 * 统一检索结果 —— 混合检索链路最终输出的 DTO。
 *
 * text 是 Child 文本（用于 Reranker 精排），
 * parentText 是 Parent 上下文（返回给 LLM）。
 *
 * score 在 RRF 融合后是 RRF 总分，在单路检索中是原始得分。
 */
public record SearchResult(
        String id,
        String text,
        double score,
        String parentId,
        String parentText
) {
    /** 平铺模式兼容构造器：Parent = Child 自身 */
    public SearchResult(String id, String text, double score) {
        this(id, text, score, id, text);
    }

    /**
     * 按需展开：当 Reranker 分数极高（≥ 此阈值）时说明 Child 本身语义匹配度已足够，
     * 直接用 Child 文本，省 Token。分数不够高时展开到 Parent 获取完整上下文。
     */
    private static final double HIGH_SCORE_THRESHOLD = 0.9;

    /** 返回 Prompt 中使用的文本：默认优先 Parent 上下文 */
    public String effectiveText() {
        return parentText != null ? parentText : text;
    }

    /**
     * 按需展开：分数 ≥ 0.9 用 Child（省 Token），否则用 Parent（要上下文）。
     * 面试要点：
     * "Reranker 0.9+ 分说明 Child 已经非常精准，再展开 Parent 是浪费 Token。
     *  这是工程上对 Small-to-Big 的成本优化——不是每次都展开，按需展开。"
     */
    public String effectiveText(boolean conditionalExpand) {
        if (!conditionalExpand) {
            return effectiveText();
        }
        if (score >= HIGH_SCORE_THRESHOLD) {
            return text;  // Child 足够精准，省 Token
        }
        return parentText != null ? parentText : text;
    }
}
