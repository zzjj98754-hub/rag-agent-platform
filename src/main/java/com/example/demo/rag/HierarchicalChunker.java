package com.example.demo.rag;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 层级文档切分器 —— Small-to-Big（父子 Chunk）策略。
 *
 * 两层结构：
 * - Parent Chunk (~2000 字)：返回给 LLM，保证上下文完整
 * - Child Chunk  (~500 字)：用于 Embedding 检索，保证精度
 *
 * Child 之间有 ~128 字重叠，防止关键信息被切在边界上。
 * Child 的 Embedding 存入 VectorStore，检索命中后返回 Parent 全文。
 *
 * 面试要点：
 * "小块检索 + 大块返回——调和了 Chunk Size 的精度-上下文矛盾。
 *  工程上只索引 Child，向量存储量不变，LLM 看到的上下文提升 4 倍。"
 */
@Component
public class HierarchicalChunker {

    private static final Logger log = LoggerFactory.getLogger(HierarchicalChunker.class);

    /** Parent Chunk 目标大小（字符数，~1024 tokens） */
    private static final int PARENT_SIZE = 2000;

    /** Child Chunk 目标大小（字符数，~256 tokens） */
    private static final int CHILD_SIZE = 500;

    /** Child 之间重叠字符数（~64 tokens） */
    private static final int OVERLAP = 128;

    /** 短文档阈值：文档少于此字符数则不做层级切分（平铺模式，Parent = Child） */
    private static final int SHORT_DOC_THRESHOLD = 2000;

    /**
     * 对文档做层级切分。
     *
     * @param content    文档全文
     * @param sourceFile 来源文件名
     * @return Child Chunk 列表（每个 Child 持有 Parent 引用）
     */
    public List<Chunk> chunk(String content, String sourceFile) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        // 短文档：不做层级切分，Parent = Child（平铺模式），避免过度设计
        if (content.length() < SHORT_DOC_THRESHOLD) {
            log.debug("短文档自适应: {} → 平铺模式 ({} 字 < {} 阈值)",
                    sourceFile, content.length(), SHORT_DOC_THRESHOLD);
            return chunkFlat(content, sourceFile);
        }

        // 1. 尝试语义边界切 Parent（优先按 Markdown 标题），无标题时退化为固定大小归组
        List<String> parents = splitSemanticParents(content);
        if (parents.isEmpty()) {
            List<String> paragraphs = splitParagraphs(content);
            parents = groupIntoParents(paragraphs);
        }

        // 3. 每个 Parent 内部切 Child
        List<Chunk> allChunks = new ArrayList<>();
        int childSeq = 0;

        for (int p = 0; p < parents.size(); p++) {
            String parentText = parents.get(p);
            String parentId = sourceFile + ":" + p;
            List<String> children = splitParentIntoChildren(parentText);

            for (int c = 0; c < children.size(); c++) {
                String childId = parentId + ":" + c;
                allChunks.add(new Chunk(
                        childId,
                        children.get(c),
                        parentId,
                        parentText,
                        childSeq++,
                        sourceFile
                ));
            }
        }

        log.debug("层级切分完成: {} → {} 个 Parent, {} 个 Child",
                sourceFile, parents.size(), allChunks.size());
        return allChunks;
    }

    // ==================== Parent 切分策略 ====================

    /**
     * 语义边界切 Parent：优先按 Markdown 标题（## / ###）切分。
     * 标题之间内容归为一个 Parent，超长 Section 自动再切。
     * 无标题时返回空列表，调用方退化为固定大小归组。
     */
    private List<String> splitSemanticParents(String content) {
        // regex 匹配 ## / ### 标题行（行首的 # 号标题）
        String[] sections = content.split("\\n(?=## )|\\n(?=### )");
        if (sections.length <= 1) {
            return List.of();  // 无标题 → 退化为固定大小切分
        }

        List<String> parents = new ArrayList<>();
        for (String section : sections) {
            String trimmed = section.strip();
            if (trimmed.isEmpty()) continue;

            // 超长 Section 再按大小切
            if (trimmed.length() > PARENT_SIZE * 2) {
                List<String> paragraphs = splitParagraphs(trimmed);
                parents.addAll(groupIntoParents(paragraphs));
            } else if (trimmed.length() > PARENT_SIZE) {
                // 略超：二分
                int mid = findSentenceBoundary(trimmed, trimmed.length() / 2, trimmed.length() / 2 + 200);
                parents.add(trimmed.substring(0, mid).strip());
                parents.add(trimmed.substring(mid).strip());
            } else {
                parents.add(trimmed);
            }
        }

        log.debug("语义边界切分: {} 个标题 Section → {} 个 Parent", sections.length, parents.size());
        return parents;
    }

    // ==================== 段落/文本切分内部方法 ====================

    /** 按双换行/段落边界拆分 */
    private List<String> splitParagraphs(String content) {
        List<String> paragraphs = new ArrayList<>();
        for (String para : content.split("\\n\\s*\\n|\\r\\n\\s*\\r\\n")) {
            String trimmed = para.strip();
            if (!trimmed.isEmpty()) {
                paragraphs.add(trimmed);
            }
        }
        return paragraphs;
    }

    /** 将段落归组为 Parent Chunk（每 Parent ~PARENT_SIZE 字） */
    private List<String> groupIntoParents(List<String> paragraphs) {
        List<String> parents = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        for (String para : paragraphs) {
            if (buf.length() + para.length() > PARENT_SIZE && !buf.isEmpty()) {
                parents.add(buf.toString().strip());
                buf.setLength(0);
            }
            if (!buf.isEmpty()) buf.append("\n\n");
            buf.append(para);
        }
        if (!buf.isEmpty()) {
            parents.add(buf.toString().strip());
        }

        return parents;
    }

    /**
     * 将 Parent 切分为 Child（滑动窗口 + 句边界对齐）。
     * 窗口 ~CHILD_SIZE 字，步进 = CHILD_SIZE - OVERLAP 字。
     */
    private List<String> splitParentIntoChildren(String parentText) {
        List<String> children = new ArrayList<>();

        int len = parentText.length();
        if (len <= CHILD_SIZE) {
            children.add(parentText);
            return children;
        }

        int start = 0;
        while (start < len) {
            int end = Math.min(start + CHILD_SIZE, len);
            boolean isLast = (end >= len);
            if (!isLast) {
                end = findSentenceBoundary(parentText, start, end);
            }

            String child = parentText.substring(start, Math.min(end, len)).strip();
            if (!child.isEmpty()) {
                children.add(child);
            }

            if (isLast) break;
            start = end - OVERLAP;
        }

        return children;
    }

    /**
     * 平铺切分模式（短文档专用）：不做 Parent/Child 两层结构。
     * 按 Child 大小切分，Parent = Child 自身。
     */
    private List<Chunk> chunkFlat(String content, String sourceFile) {
        List<Chunk> chunks = new ArrayList<>();
        int start = 0;
        int seq = 0;
        int len = content.length();
        while (start < len) {
            int end = Math.min(start + CHILD_SIZE, len);
            boolean isLast = (end >= len);
            if (!isLast) {
                end = findSentenceBoundary(content, start, end);
            }
            String text = content.substring(start, Math.min(end, len)).strip();
            if (!text.isEmpty()) {
                String id = sourceFile + ":0:" + seq;
                chunks.add(new Chunk(id, text, seq, sourceFile));
                seq++;
            }
            if (isLast) break;
            start = end - OVERLAP;
        }
        return chunks;
    }

    /**
     * 在 [start, end] 范围内找到最合适的句边界。
     * 在 end 附近向后找最近的句号、分号或段落结束位置。
     */
    private int findSentenceBoundary(String text, int start, int end) {
        // 搜索范围：end 前后各 100 字
        int searchStart = Math.max(start, end - 100);
        int searchEnd = Math.min(text.length(), end + 100);

        for (int i = end; i >= searchStart; i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '；' || c == '？' || c == '！' || c == '\n') {
                return Math.min(i + 1, text.length());
            }
        }

        // 没找到句边界 → 往前找任意标点
        for (int i = end; i >= searchStart; i--) {
            char c = text.charAt(i);
            if (c == '，' || c == '、' || c == '）' || c == ')') {
                return Math.min(i + 1, text.length());
            }
        }

        return end;
    }
}
