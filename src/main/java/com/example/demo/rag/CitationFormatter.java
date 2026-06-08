package com.example.demo.rag;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 引用格式化器 —— 将检索到的 Chunk 格式化为带编号的引用文本，拼入 Prompt。
 *
 * 面试要点：
 * "在 Prompt 中给每个 Chunk 分配 [N] 编号，要求 LLM 引用编号而非重复内容，
 *  这样 Prompt 更短、更容易校验、前端也能根据编号渲染来源链接。"
 */
@Component
public class CitationFormatter {

    /**
     * 将 Chunk 列表格式化为带编号的参考文档段，用于拼入 Prompt。
     *
     * 输出格式：
     *   [1] (来源: redis.txt) 缓存穿透是指查询不存在的数据...
     *   [2] (来源: solution.txt) 解决缓存穿透的三种方案...
     */
    public String formatReferenceSection(List<SearchResult> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("--- 参考文档 ---\n");

        for (int i = 0; i < chunks.size(); i++) {
            SearchResult chunk = chunks.get(i);
            String source = extractSource(chunk.id());
            sb.append("[").append(i + 1).append("]");
            if (!source.isEmpty()) {
                sb.append(" (来源: ").append(source).append(")");
            }
            sb.append(" ").append(chunk.effectiveText()).append("\n");
        }

        sb.append("--- 文档结束 ---\n");
        return sb.toString();
    }

    /**
     * 返回引用指令，拼在参考文档之后、用户问题之前。
     */
    public String getCitationInstruction(int maxRef) {
        return """
                请基于以上参考文档回答用户问题。

                引用规则：
                - 使用 [编号] 标注信息来源，如 [1] 或 [1][2]
                - 如果多条文档支持同一观点，标注所有相关编号
                - 只能引用 [1] 到 [%d] 这 %d 个来源，不存在其他编号
                - 如果参考文档中没有相关信息，请明确说明「参考文档中未找到相关信息」，不要猜测
                - 不要在回答中重复大段文档原文，请用自己的话总结""".formatted(maxRef, maxRef);
    }

    /**
     * 从 chunk ID 中提取来源文件名。
     * chunk ID 格式: "java.txt:0" → "java.txt"
     */
    private String extractSource(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) return "";
        int colonIdx = chunkId.lastIndexOf(':');
        if (colonIdx > 0) {
            return chunkId.substring(0, colonIdx);
        }
        return chunkId;
    }
}
