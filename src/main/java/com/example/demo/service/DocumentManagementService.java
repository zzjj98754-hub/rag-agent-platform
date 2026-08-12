package com.example.demo.service;

import com.example.demo.dto.DocumentUploadResponse;
import com.example.demo.dto.DocumentView;
import com.example.demo.dto.IngestionResult;
import com.example.demo.security.CurrentUserProvider;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentManagementService {

    private final DocumentIngestionService ingestionService;
    private final CurrentUserProvider currentUserProvider;
    private final List<DocumentContentExtractor> extractors;
    private final Set<String> allowedExtensions;

    public DocumentManagementService(
            DocumentIngestionService ingestionService,
            CurrentUserProvider currentUserProvider,
            List<DocumentContentExtractor> extractors,
            @Value("${app.document.allowed-extensions}")
            String allowedExtensions) {
        this.ingestionService = ingestionService;
        this.currentUserProvider = currentUserProvider;
        this.extractors = List.copyOf(extractors);
        this.allowedExtensions = Arrays.stream(
                        allowedExtensions.split(","))
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public List<DocumentView> listDocuments() {
        return ingestionService.listDocuments();
    }

    public DocumentUploadResponse upload(MultipartFile file) {
        String fileName = safeFileName(file);
        String extension = extension(fileName);
        if (!allowedExtensions.contains(extension)) {
            throw new IllegalArgumentException(
                    "仅支持 " + String.join(", ", allowedExtensions)
                            + " 格式");
        }
        DocumentContentExtractor extractor = extractors.stream()
                .filter(candidate -> candidate.supports(extension))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "缺少文档解析器: " + extension));
        String content;
        try {
            content = extractor.extract(file);
        } catch (IOException e) {
            throw new IllegalArgumentException("文档读取失败", e);
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("文档没有可提取的文本内容");
        }
        Long creatorId =
                currentUserProvider.requireCurrentUser().id();
        IngestionResult result = ingestionService.ingestOne(
                fileName,
                content,
                creatorId);
        return DocumentUploadResponse.from(fileName, result);
    }

    public void deleteDocument(Long documentId) {
        ingestionService.deleteDocument(documentId);
    }

    private String safeFileName(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String normalizedPath =
                original.replace('\\', '/').trim();
        int separator = normalizedPath.lastIndexOf('/');
        String normalized = normalizedPath
                .substring(separator + 1)
                .trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        if (normalized.length() > 255) {
            throw new IllegalArgumentException(
                    "文件名不能超过 255 个字符");
        }
        return normalized;
    }

    private String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0
                ? ""
                : fileName.substring(dot + 1)
                        .toLowerCase(Locale.ROOT);
    }
}
