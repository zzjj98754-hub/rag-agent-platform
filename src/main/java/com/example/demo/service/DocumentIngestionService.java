package com.example.demo.service;

import com.example.demo.dto.IngestionResult;
import com.example.demo.dto.IngestionStatus;
import com.example.demo.dto.DocumentView;
import com.example.demo.persistence.entity.DocumentEntity;
import com.example.demo.persistence.service.DocumentPersistenceService;
import com.example.demo.rag.Bm25Index;
import com.example.demo.rag.Chunk;
import com.example.demo.rag.HierarchicalChunker;
import com.example.demo.web.error.ResourceNotFoundException;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    private final HierarchicalChunker hierarchicalChunker;
    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final Bm25Index bm25Index;
    private final DocumentRegistry documentRegistry;
    private final DocumentPersistenceService documentPersistenceService;
    private final boolean startupIngestionEnabled;
    private final String configuredDocsPath;

    private final ConcurrentHashMap<String, IngestionStatus> tasks = new ConcurrentHashMap<>();

    public DocumentIngestionService(
            HierarchicalChunker hierarchicalChunker,
            EmbeddingService embeddingService,
            VectorStore vectorStore,
            Bm25Index bm25Index,
            DocumentRegistry documentRegistry,
            DocumentPersistenceService documentPersistenceService,
            @Value("${app.ingestion.startup-enabled}") boolean startupIngestionEnabled,
            @Value("${app.ingestion.docs-path}") String configuredDocsPath) {
        this.hierarchicalChunker = hierarchicalChunker;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.bm25Index = bm25Index;
        this.documentRegistry = documentRegistry;
        this.documentPersistenceService = documentPersistenceService;
        this.startupIngestionEnabled = startupIngestionEnabled;
        this.configuredDocsPath = configuredDocsPath;
    }

    /**
     * 启动时异步入库 —— 不阻塞 Spring Boot 启动。
     * 服务在几秒内就绪，入库在后台慢慢跑。
     */
    @PostConstruct
    public void init() {
        if (!startupIngestionEnabled) {
            log.info("启动时文档入库已关闭");
            warnIfPersistedDocumentsHaveNoVectors();
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                log.info("启动时文档入库开始...");
                IngestionResult result = ingestConfiguredDocuments();
                log.info("启动时文档入库完成: 成功 {} 篇, 跳过 {} 篇, 失败 {} 篇, 共 {} 个 Chunk, 耗时 {}ms",
                        result.getSucceeded().size(), result.getSkipped().size(),
                        result.getFailed().size(), result.getTotalChunks(), result.getDurationMs());
            } catch (Exception e) {
                log.error("启动时文档入库失败", e);
            }
        });
    }

    // ==================== 同步入库 ====================

    public IngestionResult ingestConfiguredDocuments() {
        return ingestAll(configuredDocsPath);
    }

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
        Map<String, String> indexedFilePaths = new LinkedHashMap<>();

        for (File file : files) {
            if (!file.isFile() || file.isHidden()) continue;
            String filePath = file.getAbsolutePath();
            markDocumentProcessing(file.getName(), filePath, null);
            try {
                String content = Files.readString(file.toPath());
                IngestionResult single = ingestOneInternal(
                        file.getName(), filePath, content, false);
                total = total.merge(single);
                if (single.getSucceeded().contains(file.getName())
                        || single.getSkipped().contains(file.getName())) {
                    indexedFilePaths.put(file.getName(), filePath);
                } else {
                    markDocumentFailed(filePath, file.getName());
                }
            } catch (IOException e) {
                log.warn("读取文档失败: {}", file.getName());
                List<IngestionResult.FailedDoc> failed = List.of(
                        new IngestionResult.FailedDoc(file.getName(), "读取失败: " + e.getMessage()));
                total = total.merge(new IngestionResult(List.of(), List.of(), failed, 0, 0));
                markDocumentFailed(filePath, file.getName());
            } catch (Exception e) {
                log.error("文档入库异常: {}", file.getName(), e);
                List<IngestionResult.FailedDoc> failed = List.of(
                        new IngestionResult.FailedDoc(file.getName(), "入库异常: " + e.getMessage()));
                total = total.merge(new IngestionResult(List.of(), List.of(), failed, 0, 0));
                markDocumentFailed(filePath, file.getName());
            }
        }

        // Stage 3: 先建立可用的关键词索引和本地向量词表。
        try {
            rebuildBm25();
            buildLocalVocabulary();

            // Stage 4/5: 再执行 Embedding，并原子式重建当前 JVM 向量索引。
            // 本地 TF-IDF 词表重建会改变维度，所以小规模知识库全量重嵌入。
            reindexAllVectors();
            verifyIndexState();
        } catch (RuntimeException indexFailure) {
            indexedFilePaths.forEach((fileName, filePath) ->
                    markDocumentFailed(filePath, fileName));
            throw indexFailure;
        }

        indexedFilePaths.forEach((fileName, filePath) -> {
            DocumentRegistry.DocumentSnapshot snapshot =
                    documentRegistry.getDocumentSnapshots().get(fileName);
            if (snapshot != null && snapshot.vectorCount() > 0) {
                markDocumentIndexed(filePath, fileName);
            } else {
                markDocumentFailed(filePath, fileName);
            }
        });

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
        return ingestOne(fileName, content, null);
    }

    public IngestionResult ingestOne(
            String fileName,
            String content,
            Long creatorId) {
        String filePath = "upload://" + fileName;
        markDocumentProcessing(fileName, filePath, creatorId);
        try {
            IngestionResult result = ingestOneInternal(
                    fileName, filePath, content, false);
            rebuildBm25();
            buildLocalVocabulary();
            reindexAllVectors();
            verifyIndexState();
            updateDocumentStatus(filePath, fileName, result);
            return result;
        } catch (RuntimeException e) {
            markDocumentFailed(filePath, fileName);
            throw e;
        }
    }

    // ==================== 异步入库 ====================

    public String ingestConfiguredDocumentsAsync() {
        return ingestAsync(configuredDocsPath);
    }

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

    public IngestionStatus requireTaskStatus(String taskId) {
        IngestionStatus status = tasks.get(taskId);
        if (status == null) {
            throw new ResourceNotFoundException("入库任务不存在: " + taskId);
        }
        return status;
    }

    /** 已索引的文档 ID 集合 */
    public java.util.Set<String> getIndexedDocuments() {
        return documentRegistry.getAllDocIds();
    }

    public List<DocumentView> listDocuments() {
        Map<String, DocumentRegistry.DocumentSnapshot> snapshots =
                documentRegistry.getDocumentSnapshots();
        return documentPersistenceService.findAll().stream()
                .map(document -> toDocumentView(
                        document,
                        snapshots.get(document.getTitle())))
                .toList();
    }

    public void deleteDocument(Long documentId) {
        DocumentEntity document =
                documentPersistenceService.requireById(documentId);
        List<String> chunkIds =
                documentRegistry.unregister(document.getTitle());
        chunkIds.forEach(vectorStore::delete);
        rebuildBm25();
        documentPersistenceService.deleteById(documentId);
        log.info(
                "文档已删除 | id={} title={} chunks={}",
                documentId,
                document.getTitle(),
                chunkIds.size());
    }

    // ==================== 内部方法 ====================

    /**
     * 单篇文档入库核心逻辑。
     *
     * @param rebuildBm25 是否在入库后重建 BM25（批量入库时由调用方统一重建）
     */
    private IngestionResult ingestOneInternal(
            String fileName,
            String filePath,
            String content,
            boolean rebuildBm25) {
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

        // Stage 3: 先注册 Child，BM25 和向量阶段由上层在完整语料准备后执行。
        Map<String, DocumentRegistry.ChunkMeta> chunkMetas = new LinkedHashMap<>();

        for (Chunk chunk : chunks) {
            chunkMetas.put(chunk.id(), new DocumentRegistry.ChunkMeta(
                    chunk.text(), chunk.parentId(), chunk.parentText()));
        }

        documentRegistry.register(fileName, fileHash, chunkMetas);

        // 单独调用兼容路径：注册后立即建立 BM25，但仍不会先做 Embedding。
        if (rebuildBm25) {
            rebuildBm25();
        }

        long duration = System.currentTimeMillis() - start;
        long parentCount = chunks.stream().map(Chunk::parentId).distinct().count();
        log.info("已切分并注册文档: {} ({} 个 Parent, {} 个 Child, 耗时 {}ms)",
                fileName, parentCount, chunks.size(), duration);

        return new IngestionResult(List.of(fileName), List.of(), List.of(),
                chunks.size(), duration);
    }

    /** 从 DocumentRegistry 全量重建 BM25 索引 */
    private void buildLocalVocabulary() {
        List<String> allChunkTexts = new ArrayList<>(
                documentRegistry.getAllChunkTexts().values());
        if (!allChunkTexts.isEmpty()) {
            embeddingService.buildVocabulary(allChunkTexts);
        }
    }

    private void reindexAllVectors() {
        List<Map.Entry<String, DocumentRegistry.ChunkMeta>> chunks =
                documentRegistry.getAllChunkMetadata().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .toList();
        if (chunks.isEmpty()) {
            return;
        }

        List<String> texts = chunks.stream()
                .map(entry -> entry.getValue().text())
                .toList();
        List<EmbeddingService.EmbeddingVector> embeddings =
                embeddingService.embedBatchWithMetadata(texts);
        if (embeddings.size() != chunks.size()
                || embeddings.stream().anyMatch(
                        EmbeddingService.EmbeddingVector::isEmpty)) {
            throw new IllegalStateException(
                    "Embedding 未生成完整有效向量: expected="
                            + chunks.size()
                            + ", actual="
                            + embeddings.size());
        }

        // Embedding 全部成功后再替换索引，避免半成品向量集对查询可见。
        chunks.forEach(entry -> vectorStore.delete(entry.getKey()));
        Map<String, EmbeddingService.EmbeddingSource> sourceByChunk =
                new LinkedHashMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map.Entry<String, DocumentRegistry.ChunkMeta> chunk = chunks.get(i);
            EmbeddingService.EmbeddingVector embedding = embeddings.get(i);
            vectorStore.add(
                    chunk.getKey(),
                    chunk.getValue().text(),
                    embedding,
                    chunk.getValue().parentId(),
                    chunk.getValue().parentText());
            sourceByChunk.put(chunk.getKey(), embedding.source());
        }

        for (String documentId : documentRegistry.getAllDocIds()) {
            List<String> chunkIds = documentRegistry.getChunkIds(documentId);
            int vectorCount = 0;
            LinkedHashSet<EmbeddingService.EmbeddingSource> sources =
                    new LinkedHashSet<>();
            for (String chunkId : chunkIds) {
                EmbeddingService.EmbeddingSource source =
                        sourceByChunk.get(chunkId);
                if (source != null) {
                    vectorCount++;
                    sources.add(source);
                }
            }
            EmbeddingService.EmbeddingSource source = sources.size() == 1
                    ? sources.iterator().next()
                    : EmbeddingService.EmbeddingSource.UNKNOWN;
            documentRegistry.markEmbedded(documentId, vectorCount, source);
        }

        EmbeddingService.EmbeddingSource source = embeddings.get(0).source();
        log.info(
                "向量索引重建完成 | chunks={} vectors={} embeddingSource={} model={}",
                chunks.size(),
                vectorStore.size(),
                source.value(),
                embeddings.get(0).modelName());
    }

    private void rebuildBm25() {
        Map<String, String> idToText = documentRegistry.getAllChunkTexts();
        Map<String, String> idToParentId = documentRegistry.getParentIds();
        Map<String, String> idToParentText = documentRegistry.getParentTexts();

        bm25Index.rebuild(idToText, idToParentId, idToParentText);

        log.debug("BM25 索引重建完成: {} 个 chunk", idToText.size());
    }

    private void verifyIndexState() {
        int documents = documentRegistry.size();
        int chunks = documentRegistry.getAllChunkTexts().size();
        int vectors = vectorStore.size();
        int bm25Documents = bm25Index.size();
        if (documents > 0 && vectors == 0) {
            log.warn(
                    "索引启动检查失败: documents={} 但 vectorStore.size=0",
                    documents);
        }
        if (documents > 0
                && (chunks == 0 || vectors == 0 || bm25Documents == 0)) {
            throw new IllegalStateException(
                    "文档入库不完整: documents="
                            + documents
                            + ", chunks="
                            + chunks
                            + ", vectors="
                            + vectors
                            + ", bm25="
                            + bm25Documents);
        }
        log.info(
                "索引启动检查通过 | documents={} chunks={} vectors={} bm25={}",
                documents,
                chunks,
                vectors,
                bm25Documents);
    }

    private void warnIfPersistedDocumentsHaveNoVectors() {
        try {
            int persistedDocuments = documentPersistenceService.findAll().size();
            if (persistedDocuments > 0 && vectorStore.size() == 0) {
                log.warn(
                        "数据库存在 {} 篇文档，但当前 JVM VectorStore 为空；"
                                + "请启用启动入库或手动触发重新索引",
                        persistedDocuments);
            }
        } catch (Exception e) {
            log.debug("启动索引检查跳过: {}", e.getMessage());
        }
    }

    private void markDocumentProcessing(String title, String filePath, Long creatorId) {
        try {
            documentPersistenceService.markProcessing(title, filePath, creatorId);
        } catch (Exception e) {
            log.error("MySQL 记录文档入库状态失败 | document={} status=PROCESSING: {}",
                    title, e.getMessage());
        }
    }

    private void updateDocumentStatus(
            String filePath,
            String fileName,
            IngestionResult result) {
        boolean indexed = result.getSucceeded().contains(fileName)
                || result.getSkipped().contains(fileName);
        if (indexed) {
            markDocumentIndexed(filePath, fileName);
        } else {
            markDocumentFailed(filePath, fileName);
        }
    }

    private void markDocumentIndexed(String filePath, String fileName) {
        try {
            documentPersistenceService.markIndexed(filePath);
        } catch (Exception e) {
            log.error("MySQL 更新文档状态失败 | document={} status=INDEXED: {}",
                    fileName, e.getMessage());
        }
    }

    private void markDocumentFailed(String filePath, String fileName) {
        try {
            documentPersistenceService.markFailed(filePath);
        } catch (Exception e) {
            log.error("MySQL 更新文档状态失败 | document={} status=FAILED: {}",
                    fileName, e.getMessage());
        }
    }

    private DocumentView toDocumentView(
            DocumentEntity document,
            DocumentRegistry.DocumentSnapshot snapshot) {
        int chunkCount =
                snapshot == null ? 0 : snapshot.chunkCount();
        int vectorCount =
                snapshot == null ? 0 : snapshot.vectorCount();
        String embeddingSource = snapshot == null
                ? "unknown"
                : snapshot.embeddingSource();
        String embeddingStatus;
        if ("FAILED".equals(document.getStatus())) {
            embeddingStatus = "FAILED";
        } else if ("PROCESSING".equals(document.getStatus())) {
            embeddingStatus = "PROCESSING";
        } else {
            embeddingStatus = chunkCount > 0
                    && vectorCount == chunkCount
                    ? "READY"
                    : "NOT_LOADED";
        }
        return new DocumentView(
                document.getId(),
                document.getTitle(),
                document.getFilePath(),
                document.getStatus(),
                document.getCreatorId(),
                document.getCreateTime(),
                chunkCount,
                vectorCount,
                embeddingSource,
                embeddingStatus);
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
