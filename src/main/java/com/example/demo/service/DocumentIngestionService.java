package com.example.demo.service;

import com.example.demo.dto.IngestionResult;
import com.example.demo.dto.IngestionStatus;
import com.example.demo.rag.Bm25Index;
import com.example.demo.rag.Chunk;
import com.example.demo.rag.HierarchicalChunker;
import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 文档入库管线编排器 —— 增量索引 + 变更检测 + 异步执行。
 *
 * 管线四阶段：Load（读取+哈希）→ Chunk（层级切分）→ Embed（批量向量化）→ Index（VectorStore + BM25）
 *
 * 面试直接说：
 *   "入库不是启动时读一遍文件。我做的是增量索引（SHA-256 变更检测）
 *    + 异步不阻塞 + 幂等覆盖写入 + 失败清单分离。"
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);
    private static final String DOCS_PATH = "D:/docs";

    @Autowired
    private HierarchicalChunker hierarchicalChunker;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private Bm25Index bm25Index;

    @Autowired
    private DocumentRegistry documentRegistry;

    private final ConcurrentHashMap<String, IngestionStatus> tasks = new ConcurrentHashMap<>();

    /**
     * 启动时异步入库 —— 不阻塞 Spring Boot 启动。
     * 服务在几秒内就绪，入库在后台慢慢跑。
     */
    @PostConstruct
    public void init() {
        CompletableFuture.runAsync(() -> {
            try {
                log.info("启动时文档入库开始...");
                IngestionResult result = ingestAll(DOCS_PATH);
                log.info("启动时文档入库完成: 成功 {} 篇, 跳过 {} 篇, 失败 {} 篇, 共 {} 个 Chunk, 耗时 {}ms",
                        result.getSucceeded().size(), result.getSkipped().size(),
                        result.getFailed().size(), result.getTotalChunks(), result.getDurationMs());
            } catch (Exception e) {
                log.error("启动时文档入库失败", e);
            }
        });
    }

    // ==================== 同步入库 ====================

    /**
     * 全量扫描目录，增量入库（只处理新增和变更的文档）。
     */
    public IngestionResult ingestAll(String docsPath) {
        long start = System.currentTimeMillis();

        File docsDir = new File(docsPath);
        File[] files = docsDir.listFiles();
        if (files == null) {
            log.warn("文档目录不存在或为空: {}", docsPath);
            return IngestionResult.empty();
        }

        IngestionResult total = IngestionResult.empty();
        List<String> allChunkTextsForVocab = new ArrayList<>();

        for (File file : files) {
            if (!file.isFile()) continue;
            try {
                String content = Files.readString(file.toPath());
                IngestionResult single = ingestOneInternal(file.getName(), content, false);
                total = total.merge(single);

                // 收集所有 chunk 文本用于后续 buildVocabulary
                if (!single.getSucceeded().contains(file.getName())) continue;
                // 从 registry 获取刚注册的 chunk 文本
                // (ingestOneInternal 已完成 register，文本已在 registry 中)
            } catch (IOException e) {
                log.warn("读取文档失败: {}", file.getName());
                List<IngestionResult.FailedDoc> failed = List.of(
                        new IngestionResult.FailedDoc(file.getName(), "读取失败: " + e.getMessage()));
                total = total.merge(new IngestionResult(List.of(), List.of(), failed, 0, 0));
            }
        }

        // 收集所有 chunk 文本用于 TF-IDF 词表构建
        Map<String, String> allTexts = documentRegistry.getAllChunkTexts();
        if (!allTexts.isEmpty()) {
            allChunkTextsForVocab.addAll(allTexts.values());
            embeddingService.buildVocabulary(allChunkTextsForVocab);
        }

        // 全量重建 BM25（含 parentId + parentText 映射）
        rebuildBm25();

        long duration = System.currentTimeMillis() - start;
        log.info("入库完成: 成功 {} 篇, 跳过 {} 篇, 失败 {} 篇, Chunk 总数 {}, 耗时 {}ms",
                total.getSucceeded().size(), total.getSkipped().size(),
                total.getFailed().size(), total.getTotalChunks(), duration);

        return new IngestionResult(total.getSucceeded(), total.getSkipped(),
                total.getFailed(), total.getTotalChunks(), duration);
    }

    /**
     * 单篇文档入库（API 上传触发）。
     */
    public IngestionResult ingestOne(String fileName, String content) {
        return ingestOneInternal(fileName, content, true);
    }

    // ==================== 异步入库 ====================

    /**
     * 异步入库 —— 返回 taskId 用于轮询进度。
     */
    public String ingestAsync(String docsPath) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        IngestionStatus status = new IngestionStatus(taskId);
        tasks.put(taskId, status);

        CompletableFuture.runAsync(() -> {
            try {
                status.start(1);
                IngestionResult result = ingestAll(docsPath);
                status.complete(result);
            } catch (Exception e) {
                log.error("异步入库失败: taskId={}", taskId, e);
                status.fail(e.getMessage());
            }
        });

        return taskId;
    }

    /** 查询异步入库任务状态 */
    public IngestionStatus getTaskStatus(String taskId) {
        return tasks.get(taskId);
    }

    /** 已索引的文档 ID 集合 */
    public java.util.Set<String> getIndexedDocuments() {
        return documentRegistry.getAllDocIds();
    }

    // ==================== 内部方法 ====================

    /**
     * 单篇文档入库核心逻辑。
     *
     * @param rebuildBm25 是否在入库后重建 BM25（批量入库时由调用方统一重建）
     */
    private IngestionResult ingestOneInternal(String fileName, String content, boolean rebuildBm25) {
        long start = System.currentTimeMillis();

        // Stage 1: Load — 计算文件哈希
        String fileHash = sha256Hex(content);

        // 增量索引：未变更则跳过
        if (!documentRegistry.isChanged(fileName, fileHash)) {
            log.debug("文档未变更，跳过: {}", fileName);
            return new IngestionResult(List.of(), List.of(fileName), List.of(), 0,
                    System.currentTimeMillis() - start);
        }

        // 如果文档之前已索引，先清理旧 Chunk
        List<String> oldChunkIds = documentRegistry.unregister(fileName);
        for (String oldId : oldChunkIds) {
            vectorStore.delete(oldId);
        }
        if (!oldChunkIds.isEmpty()) {
            log.debug("已清理旧 Chunk: {} 个 (来自 {})", oldChunkIds.size(), fileName);
        }

        // Stage 2: Chunk — 层级切分
        List<Chunk> chunks;
        try {
            chunks = hierarchicalChunker.chunk(content, fileName);
        } catch (Exception e) {
            log.error("文档切分失败: {}", fileName, e);
            List<IngestionResult.FailedDoc> failed = List.of(
                    new IngestionResult.FailedDoc(fileName, "切分失败: " + e.getMessage()));
            return new IngestionResult(List.of(), List.of(), failed, 0,
                    System.currentTimeMillis() - start);
        }

        if (chunks.isEmpty()) {
            log.warn("文档切分为空: {}", fileName);
            return new IngestionResult(List.of(), List.of(), List.of(), 0,
                    System.currentTimeMillis() - start);
        }

        // Stage 3: Embed — 批量向量化
        List<String> childTexts = chunks.stream().map(Chunk::text).toList();
        List<float[]> vectors;
        try {
            vectors = embeddingService.embedBatch(childTexts);
        } catch (Exception e) {
            log.error("文档 Embedding 失败: {}", fileName, e);
            List<IngestionResult.FailedDoc> failed = List.of(
                    new IngestionResult.FailedDoc(fileName, "Embedding 失败: " + e.getMessage()));
            return new IngestionResult(List.of(), List.of(), failed, 0,
                    System.currentTimeMillis() - start);
        }

        if (vectors.isEmpty() || vectors.size() != chunks.size()) {
            log.error("Embedding 返回数量不匹配: 期望 {} 条, 实际 {} 条 (来自 {})",
                    chunks.size(), vectors.size(), fileName);
            List<IngestionResult.FailedDoc> failed = List.of(
                    new IngestionResult.FailedDoc(fileName, "Embedding 返回数量不匹配"));
            return new IngestionResult(List.of(), List.of(), failed, 0,
                    System.currentTimeMillis() - start);
        }

        // Stage 4: Index — 写入 VectorStore + 注册到 DocumentRegistry
        int dimension = embeddingService.dimension();
        String modelName = embeddingService.modelName();
        Map<String, DocumentRegistry.ChunkMeta> chunkMetas = new LinkedHashMap<>();

        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            vectorStore.add(chunk.id(), chunk.text(), vectors.get(i),
                    dimension, modelName, chunk.parentId(), chunk.parentText());
            chunkMetas.put(chunk.id(), new DocumentRegistry.ChunkMeta(
                    chunk.text(), chunk.parentId(), chunk.parentText()));
        }

        documentRegistry.register(fileName, fileHash, chunkMetas);

        // 需要时重建 BM25
        if (rebuildBm25) {
            rebuildBm25();
        }

        long duration = System.currentTimeMillis() - start;
        long parentCount = chunks.stream().map(Chunk::parentId).distinct().count();
        log.info("已入库文档: {} ({} 个 Parent, {} 个 Child, 耗时 {}ms)",
                fileName, parentCount, chunks.size(), duration);

        return new IngestionResult(List.of(fileName), List.of(), List.of(),
                chunks.size(), duration);
    }

    /** 从 DocumentRegistry 全量重建 BM25 索引 */
    private void rebuildBm25() {
        Map<String, String> idToText = documentRegistry.getAllChunkTexts();
        Map<String, String> idToParentId = documentRegistry.getParentIds();
        Map<String, String> idToParentText = documentRegistry.getParentTexts();

        bm25Index.rebuild(idToText);
        bm25Index.setParentIds(idToParentId);
        bm25Index.setParentTexts(idToParentText);

        log.debug("BM25 索引重建完成: {} 个 chunk", idToText.size());
    }

    // ==================== 工具方法 ====================

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
