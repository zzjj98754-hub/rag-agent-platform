package com.example.demo.rag;

/**
 * 层级化 Chunk 数据结构 —— Small-to-Big 检索策略的数据基础。
 *
 * Child Chunk (text) 用于向量检索 —— 小块语义聚焦，检索精度高。
 * Parent Chunk (parentText) 用于返回 LLM —— 大块上下文完整。
 *
 * 面试要点：
 * "只索引 Child 的 Embedding，Parent 文本作为元数据附带。
 *  向量存储量不变，LLM 看到的上下文却提升了 4 倍。"
 */
public record Chunk(
        String id,           // Child ID: "spring.txt:0:2"
        String text,         // Child 文本 (~500 字，用于 Embedding 检索)
        String parentId,     // Parent ID: "spring.txt:0"
        String parentText,   // Parent 全文 (~2000 字，返回给 LLM)
        int chunkIndex,      // Child 在当前文档中的序号
        String sourceFile    // 来源文件名
) {
    /** 简短版构造器：Parent = Child（平铺模式，兼容旧逻辑） */
    public Chunk(String id, String text, int chunkIndex, String sourceFile) {
        this(id, text, id, text, chunkIndex, sourceFile);
    }
}
