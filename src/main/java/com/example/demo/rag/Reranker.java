package com.example.demo.rag;

import java.util.List;

/**
 * 重排序接口 —— 用 Cross-Encoder 对粗排候选做精细打分。
 *
 * Bi-Encoder（检索）回答「哪些文档相关」，
 * Cross-Encoder（Reranker）回答「哪些文档能真正回答用户问题」。
 *
 * 实现可以是：
 * - BGE-Reranker API（硅基流动，免费）
 * - Cohere Rerank API
 * - 本地 Cross-Encoder 模型
 * - RankGPT（LLM 打分）
 */
public interface Reranker {

    /**
     * 对候选文档列表精细重排序。
     *
     * @param query      用户原始查询
     * @param documents  粗排候选文档（来自混合检索 / RRF 融合）
     * @param topK       最终返回数量
     * @return 按 Cross-Encoder 相关性降序排列的结果
     */
    List<SearchResult> rerank(String query, List<SearchResult> documents, int topK);
}
