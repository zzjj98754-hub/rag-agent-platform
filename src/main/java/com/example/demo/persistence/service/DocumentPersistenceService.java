package com.example.demo.persistence.service;

import com.example.demo.persistence.entity.DocumentEntity;
import com.example.demo.persistence.mapper.DocumentMapper;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import com.example.demo.persistence.entity.IndexTaskEntity;
import com.example.demo.persistence.mapper.IndexTaskMapper;
import com.example.demo.web.error.ResourceNotFoundException;

@Service
public class DocumentPersistenceService {

    public enum Status {
        PROCESSING,
        INDEXED,
        FAILED
    }

    private final DocumentMapper documentMapper;
    private final OutboxEventService outboxEventService;
    private final IndexTaskMapper indexTaskMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public DocumentPersistenceService(DocumentMapper documentMapper, OutboxEventService outboxEventService, IndexTaskMapper indexTaskMapper) {
        this.documentMapper = documentMapper;
        this.outboxEventService = outboxEventService;
        this.indexTaskMapper = indexTaskMapper;
    }

    /** Compatibility constructor for focused persistence tests that do not exercise async indexing. */
    public DocumentPersistenceService(DocumentMapper documentMapper, OutboxEventService outboxEventService) {
        this(documentMapper, outboxEventService, null);
    }

    /** Writes document PROCESSING state and the asynchronous index command atomically. */
    @Transactional
    public String requestAsyncIndex(String taskId, String title, String filePath, String content, Long creatorId) {
        DocumentEntity document = markProcessing(title, filePath, content, creatorId);
        String hash = sha256(content);
        IndexTaskEntity task = new IndexTaskEntity(); task.setTaskId(taskId); task.setDocumentId(document.getId());
        task.setDocumentVersion(document.getDocumentVersion()); task.setContentHash(hash); task.setStatus("PENDING"); task.setMaxRetries(3);
        indexTaskMapper.insert(task);
        outboxEventService.documentIndexRequested(taskId, document.getId(), document.getDocumentVersion(), title, filePath, content, creatorId, hash);
        return taskId;
    }

    @Transactional
    public DocumentEntity markProcessing(String title, String filePath, String content, Long creatorId) {
        DocumentEntity document = new DocumentEntity();
        document.setTitle(requireText(title, "title", 255));
        document.setFilePath(requireText(filePath, "filePath", 512));
        document.setStatus(Status.PROCESSING.name());
        document.setCreatorId(creatorId);
        document.setContent(content);
        document.setContentHash(sha256(content));
        DocumentEntity existing = documentMapper.findByFilePath(filePath);
        document.setDocumentVersion(existing == null ? 1 : existing.getDocumentVersion() + 1);
        documentMapper.upsert(document);
        return documentMapper.findByFilePath(document.getFilePath());
    }

    public IndexTaskEntity findTask(String taskId) { return indexTaskMapper.findById(taskId); }
    public boolean claimTask(String taskId, String workerId) { return indexTaskMapper.claim(taskId, workerId, 300) > 0; }
    public void finishTask(String taskId, String status, String code, String reason) { indexTaskMapper.finish(taskId, status, code, reason); }
    @Transactional
    public boolean retryTask(String taskId) {
        IndexTaskEntity task = indexTaskMapper.findById(taskId);
        if (task == null || !("FAILED".equals(task.getStatus()) || "DEAD".equals(task.getStatus()))) return false;
        DocumentEntity document = documentMapper.findById(task.getDocumentId());
        if (document == null || document.getDocumentVersion() != task.getDocumentVersion()) return false;
        if (indexTaskMapper.retry(taskId) == 0) return false;
        outboxEventService.documentIndexRequested(taskId, document.getId(), document.getDocumentVersion(), document.getTitle(), document.getFilePath(), document.getContent(), document.getCreatorId(), document.getContentHash());
        return true;
    }
    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("内容哈希计算失败", e); }
    }

    /** Synchronous ingestion compatibility path. */
    @Transactional
    public DocumentEntity markProcessing(String title, String filePath, Long creatorId) {
        return markProcessing(title, filePath, "", creatorId);
    }

    @Transactional
    public void markIndexed(String filePath) {
        updateStatus(filePath, Status.INDEXED);
    }

    @Transactional
    public void markFailed(String filePath) {
        updateStatus(filePath, Status.FAILED);
    }

    public DocumentEntity findByFilePath(String filePath) {
        return filePath == null || filePath.isBlank()
                ? null
                : documentMapper.findByFilePath(filePath);
    }

    public List<DocumentEntity> findByStatus(String status) {
        if (status == null || status.isBlank()) {
            return List.of();
        }
        return documentMapper.findByStatus(status.trim().toUpperCase(Locale.ROOT));
    }

    public List<DocumentEntity> findAll() {
        return documentMapper.findAll();
    }

    public DocumentEntity requireById(Long id) {
        DocumentEntity document =
                id == null ? null : documentMapper.findById(id);
        if (document == null) {
            throw new ResourceNotFoundException(
                    "文档不存在: " + id);
        }
        return document;
    }

    @Transactional
    public void deleteById(Long id) {
        if (id == null || documentMapper.deleteById(id) == 0) {
            throw new ResourceNotFoundException(
                    "文档不存在: " + id);
        }
    }

    private void updateStatus(String filePath, Status status) {
        String normalizedPath = requireText(filePath, "filePath", 512);
        if (documentMapper.updateStatusByFilePath(normalizedPath, status.name()) == 0) {
            throw new IllegalArgumentException("文档不存在: " + normalizedPath);
        }
    }

    private String requireText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    fieldName + " 不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }
}
