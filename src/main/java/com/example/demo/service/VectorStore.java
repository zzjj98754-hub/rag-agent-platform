package com.example.demo.service;

import java.util.List;
import java.util.Set;

/**
 * 向量存储抽象接口 —— 定义向量存取和检索的标准契约。
 *
 * 后端可替换：ConcurrentHashMap（demo）、Redis Stack（生产起步）、Milvus（大规模）。
 * 上层代码（ChatService / HybridRetriever / SearchTool）只依赖此接口，
 * 切换后端无需修改业务逻辑。
 *
 * 面试要点：
 * - 接口抽象让向量存储从"一个 HashMap"变成可演进的子系统
 * - Entry 带 dimension + modelName 元数据，支持多模型共存和渐进式迁移
 * - search 默认按 cosine 相似度排序，具体索引算法由实现决定
 */
public interface VectorStore {

    /** 向量条目 —— 含完整元数据 */
    record Entry(String id, String text, float[] embedding,
                 int dimension, String modelName,
                 EmbeddingService.EmbeddingSource embeddingSource,
                 String parentId, String parentText) {

        public Entry(
                String id,
                String text,
                float[] embedding,
                int dimension,
                String modelName,
                String parentId,
                String parentText) {
            this(
                    id,
                    text,
                    embedding,
                    dimension,
                    modelName,
                    EmbeddingService.EmbeddingSource.UNKNOWN,
                    parentId,
                    parentText);
        }
    }

    /** 检索结果 —— 按得分降序排列 */
    record Result(String id, String text, double score,
                  String parentId, String parentText) {

        /** 返回 Prompt 中使用的文本（优先用 Parent 上下文） */
        public String effectiveText() {
            return parentText != null ? parentText : text;
        }
    }

    /**
     * 存入文档片段及其向量（含完整元数据）。
     * ID 相同则覆盖。
     *
     * @param dimension 向量维度（用于 schema 校验）
     * @param modelName 模型标识（用于多模型共存时的分区检索）
     */
    void add(String id, String text, float[] embedding,
             int dimension, String modelName,
             String parentId, String parentText);

    default void add(
            String id,
            String text,
            EmbeddingService.EmbeddingVector embedding,
            String parentId,
            String parentText) {
        add(
                id,
                text,
                embedding.values(),
                embedding.dimension(),
                embedding.modelName(),
                parentId,
                parentText);
    }

    /**
     * 余弦相似度 Top-K 检索 —— 在所有模型中检索。
     * 单模型场景直接使用此方法。
     */
    List<Result> search(float[] queryEmbedding, int topK);

    default List<Result> search(
            EmbeddingService.EmbeddingVector queryEmbedding,
            int topK) {
        return search(queryEmbedding.values(), topK);
    }

    /** 删除指定 ID 的向量（文档更新/删除时用于清理旧 Chunk） */
    void delete(String id);

    /** 向量总数 */
    int size();

    default Set<EmbeddingService.EmbeddingSource> embeddingSources() {
        return Set.of();
    }
}
