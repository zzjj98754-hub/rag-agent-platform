package com.example.demo.controller;

import com.example.demo.dto.IngestionResult;
import com.example.demo.dto.IngestionStatus;
import com.example.demo.service.DocumentIngestionService;
import com.example.demo.service.DocumentManagementService;
import com.example.demo.dto.DocumentUploadResponse;
import com.example.demo.dto.DocumentView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;
import java.util.List;

/**
 * 管理端点 —— 文档入库触发、状态查询、文档清单。
 */
@RestController
@RequestMapping("/admin")
@Validated
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final DocumentIngestionService ingestionService;
    private final DocumentManagementService documentManagementService;

    public AdminController(
            DocumentIngestionService ingestionService,
            DocumentManagementService documentManagementService) {
        this.ingestionService = ingestionService;
        this.documentManagementService = documentManagementService;
    }

    /** 同步全量入库 */
    @PostMapping("/documents/ingest")
    public Map<String, Object> ingest() {
        log.info("收到同步入库请求");
        IngestionResult result = ingestionService.ingestConfiguredDocuments();
        return Map.of(
                "success", true,
                "succeeded", result.getSucceeded(),
                "skipped", result.getSkipped(),
                "failed", result.getFailed().stream()
                        .map(f -> Map.of("docName", f.docName(), "reason", f.reason()))
                        .toList(),
                "totalChunks", result.getTotalChunks(),
                "durationMs", result.getDurationMs()
        );
    }

    /** 异步入库 —— 返回 taskId */
    @PostMapping("/documents/ingest/async")
    public Map<String, Object> ingestAsync() {
        String taskId = ingestionService.ingestConfiguredDocumentsAsync();
        log.info("收到异步入库请求，taskId={}", taskId);
        return Map.of("success", true, "taskId", taskId);
    }

    /** 查询异步入库进度 */
    @GetMapping("/documents/status/{taskId}")
    public Map<String, Object> getStatus(
        @NotBlank @Size(max = 64) @PathVariable String taskId) {
        IngestionStatus status = ingestionService.requireTaskStatus(taskId);
        Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("taskId", status.getTaskId());
        resp.put("state", status.getState().name());
        resp.put("processed", status.getProcessed());
        resp.put("total", status.getTotal());
        resp.put("elapsedMs", status.getElapsedMs());
        if (status.getResult() != null) {
            IngestionResult r = status.getResult();
            resp.put("succeeded", r.getSucceeded());
            resp.put("skipped", r.getSkipped());
            resp.put("failed", r.getFailed().stream()
                    .map(f -> Map.of("docName", f.docName(), "reason", f.reason()))
                    .toList());
            resp.put("totalChunks", r.getTotalChunks());
        }
        if (status.getError() != null) {
            resp.put("error", status.getError());
        }
        return resp;
    }

    /** 已索引文档清单 */
    @GetMapping("/documents")
    public Map<String, Object> listDocuments() {
        List<DocumentView> documents =
                documentManagementService.listDocuments();
        return Map.of(
                "documents", documents,
                "total", documents.size());
    }

    @PostMapping(
            value = "/documents/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentUploadResponse uploadDocument(
            @RequestParam("file") MultipartFile file) {
        return documentManagementService.upload(file);
    }

    @DeleteMapping("/documents/{documentId}")
    public Map<String, Object> deleteDocument(
            @PathVariable Long documentId) {
        documentManagementService.deleteDocument(documentId);
        return Map.of(
                "success", true,
                "documentId", documentId);
    }
}
