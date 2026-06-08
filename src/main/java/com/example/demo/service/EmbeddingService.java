package com.example.demo.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本向量化服务 —— 把文本映射为固定维度的浮点向量。
 *
 * 面试要点：
 * - embedding 就是文本→向量的映射，语义相近的文本向量距离也近
 * - 实际项目常用：OpenAI text-embedding-3-small / Cohere Embed / BGE 等
 * - 这里用 TF-IDF 做演示（零依赖），替换为 API 只需改实现
 */
public interface EmbeddingService {

    /** 向量维度（vocabulary 构建后确定） */
    int dimension();

    /** 模型标识（用于版本管理和日志追踪） */
    default String modelName() {
        return getClass().getSimpleName();
    }

    /**
     * 健康检查 —— 判断当前服务是否可用。
     * 用于熔断器/降级决策：主服务不可用时自动切到备选。
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * 基于语料库构建词表（TF-IDF 实现需要，API 实现忽略）。
     * 必须在所有 embed() 调用之前执行一次。
     */
    default void buildVocabulary(List<String> chunks) {
        // API-based 实现无需词表
    }

    /** 将文本转为向量 */
    float[] embed(String text);

    /**
     * 批量文本 → 向量。默认逐条调用 embed()，API 实现应覆盖为单次批量请求。
     */
    default List<float[]> embedBatch(List<String> texts) {
        List<float[]> results = new ArrayList<>();
        for (String text : texts) {
            results.add(embed(text));
        }
        return results;
    }
}
