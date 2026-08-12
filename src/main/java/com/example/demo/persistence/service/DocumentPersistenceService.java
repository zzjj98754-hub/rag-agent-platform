package com.example.demo.persistence.service;

import com.example.demo.persistence.entity.DocumentEntity;
import com.example.demo.persistence.mapper.DocumentMapper;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.demo.web.error.ResourceNotFoundException;

@Service
public class DocumentPersistenceService {

    public enum Status {
        PROCESSING,
        INDEXED,
        FAILED
    }

    private final DocumentMapper documentMapper;

    public DocumentPersistenceService(DocumentMapper documentMapper) {
        this.documentMapper = documentMapper;
    }

    @Transactional
    public DocumentEntity markProcessing(
            String title,
            String filePath,
            Long creatorId) {
        DocumentEntity document = new DocumentEntity();
        document.setTitle(requireText(title, "title", 255));
        document.setFilePath(requireText(filePath, "filePath", 512));
        document.setStatus(Status.PROCESSING.name());
        document.setCreatorId(creatorId);
        documentMapper.upsert(document);
        return documentMapper.findByFilePath(document.getFilePath());
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
