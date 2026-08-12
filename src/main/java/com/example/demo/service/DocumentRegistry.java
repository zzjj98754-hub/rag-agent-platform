package com.example.demo.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 文档注册表 —— 追踪已索引文档的元数据。
 *
 * 职责：
 * 1. 变更检测：SHA-256 文件哈希对比，判断文档是否需要重新入库
 * 2. Chunk 追踪：记录每个 docId 下有哪些 chunkId，用于更新时的旧 Chunk 清理
 * 3. BM25 重建：保存 ChunkMeta（text + parentId + parentText），供增量索引后全量重建 BM25
 *
 * 面试直接说：
 *   "没有注册表，增量索引就是空谈——你连'这篇文档我索引过没有'都不知道。"
 */
@Component
public class DocumentRegistry {

    private static final Logger log = LoggerFactory.getLogger(DocumentRegistry.class);

    private final ConcurrentHashMap<String, DocRecord> docs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ChunkMeta> chunkMeta = new ConcurrentHashMap<>();

    private record DocRecord(
            String fileHash,
            List<String> chunkIds,
            long ingestedAt,
            int vectorCount,
            EmbeddingService.EmbeddingSource embeddingSource) {}

    public record DocumentSnapshot(
            String documentId,
            int chunkCount,
            int vectorCount,
            String embeddingSource,
            long ingestedAt) {
    }

    /**
     * Chunk 元数据 —— 用于 BM25 全量重建时提供 text + parentId + parentText。
     */
    public record ChunkMeta(String text, String parentId, String parentText) {}

    /**
     * 判断文档是否变更。未注册的文档视为"已变更"（首次入库）。
     */
    public boolean isChanged(String docId, String newHash) {
        DocRecord record = docs.get(docId);
        if (record == null) return true;
        return !record.fileHash().equals(newHash);
    }

    /**
     * 注册文档及其 Chunk 信息。如果已存在则覆盖（更新场景）。
     *
     * @param docId         文档标识（通常是文件名）
     * @param fileHash      SHA-256 文件内容哈希
     * @param chunkMetas    chunkId → ChunkMeta(text, parentId, parentText)
     */
    public void register(String docId, String fileHash, Map<String, ChunkMeta> chunkMetas) {
        List<String> chunkIds = new ArrayList<>(chunkMetas.keySet());
        docs.put(docId, new DocRecord(
                fileHash,
                chunkIds,
                System.currentTimeMillis(),
                0,
                EmbeddingService.EmbeddingSource.UNKNOWN));
        chunkMeta.putAll(chunkMetas);
        log.debug("已注册文档: {} ({} 个 chunk, hash={})", docId, chunkIds.size(),
                fileHash.substring(0, Math.min(8, fileHash.length())));
    }

    public void markEmbedded(
            String docId,
            int vectorCount,
            EmbeddingService.EmbeddingSource source) {
        docs.computeIfPresent(docId, (ignored, current) -> new DocRecord(
                current.fileHash(),
                current.chunkIds(),
                current.ingestedAt(),
                Math.max(0, vectorCount),
                source == null
                        ? EmbeddingService.EmbeddingSource.UNKNOWN
                        : source));
    }

    /**
     * 注销文档 —— 返回旧的 chunkId 列表用于清理 VectorStore。
     * 同时从 chunkMeta 中移除这些 chunk。
     */
    public List<String> unregister(String docId) {
        DocRecord old = docs.remove(docId);
        if (old == null) return List.of();
        for (String chunkId : old.chunkIds()) {
            chunkMeta.remove(chunkId);
        }
        log.debug("已注销文档: {} ({} 个 chunk)", docId, old.chunkIds().size());
        return old.chunkIds();
    }

    // ==================== BM25 重建所需数据 ====================

    /** 获取全部 chunkId → text 映射（用于 BM25 关键词索引） */
    public Map<String, String> getAllChunkTexts() {
        Map<String, String> map = new LinkedHashMap<>();
        for (var entry : chunkMeta.entrySet()) {
            map.put(entry.getKey(), entry.getValue().text());
        }
        return map;
    }

    /** 获取 chunkId → parentId 映射（仅包含 parentId ≠ chunkId 的条目） */
    public Map<String, String> getParentIds() {
        Map<String, String> map = new HashMap<>();
        for (var entry : chunkMeta.entrySet()) {
            if (!entry.getValue().parentId().equals(entry.getKey())) {
                map.put(entry.getKey(), entry.getValue().parentId());
            }
        }
        return map;
    }

    /** 获取 chunkId → parentText 映射（仅包含 parentText ≠ chunkText 的条目） */
    public Map<String, String> getParentTexts() {
        Map<String, String> map = new HashMap<>();
        for (var entry : chunkMeta.entrySet()) {
            if (!entry.getValue().parentText().equals(entry.getValue().text())) {
                map.put(entry.getKey(), entry.getValue().parentText());
            }
        }
        return map;
    }

    public Map<String, ChunkMeta> getAllChunkMetadata() {
        return Map.copyOf(chunkMeta);
    }

    public List<String> getChunkIds(String docId) {
        DocRecord record = docs.get(docId);
        return record == null ? List.of() : record.chunkIds();
    }

    // ==================== 查询 ====================

    /** 已索引的文档 ID 集合 */
    public Set<String> getAllDocIds() {
        return Set.copyOf(docs.keySet());
    }

    public Map<String, DocumentSnapshot> getDocumentSnapshots() {
        Map<String, DocumentSnapshot> snapshots =
                new LinkedHashMap<>();
        docs.forEach((documentId, record) -> snapshots.put(
                documentId,
                new DocumentSnapshot(
                        documentId,
                        record.chunkIds().size(),
                        record.vectorCount(),
                        record.embeddingSource().value(),
                        record.ingestedAt())));
        return Map.copyOf(snapshots);
    }

    /** 已注册的文档数 */
    public int size() {
        return docs.size();
    }

    /** 清空注册表（用于全量重建场景） */
    public void clear() {
        docs.clear();
        chunkMeta.clear();
        log.info("DocumentRegistry 已清空");
    }
}
