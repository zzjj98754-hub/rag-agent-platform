package com.example.demo.controller;

import com.example.demo.dto.IngestionResult;
import com.example.demo.dto.IngestionStatus;
import com.example.demo.service.DocumentIngestionService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端点 —— 文档入库触发、状态查询、文档清单。
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    @Autowired
    private DocumentIngestionService ingestionService;

    /** 同步全量入库 */
    @PostMapping("/documents/ingest")
    public Map<String, Object> ingest() {
        log.info("收到同步入库请求");
        IngestionResult result = ingestionService.ingestAll("D:/docs");
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
        String taskId = ingestionService.ingestAsync("D:/docs");
        log.info("收到异步入库请求，taskId={}", taskId);
        return Map.of("success", true, "taskId", taskId);
    }

    /** 查询异步入库进度 */
    @GetMapping("/documents/status/{taskId}")
    public Map<String, Object> getStatus(@PathVariable String taskId) {
        IngestionStatus status = ingestionService.getTaskStatus(taskId);
        if (status == null) {
            return Map.of("error", "任务不存在: " + taskId);
        }
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
        java.util.Set<String> docs = ingestionService.getIndexedDocuments();
        return Map.of("documents", docs, "total", docs.size());
    }

    /** 单篇文档入库（上传文本内容） */
    @PostMapping("/documents/upload")
    public Map<String, Object> uploadDocument(@RequestParam String fileName,
                                              @RequestParam String content) {
        log.info("收到文档上传请求: {}", fileName);
        IngestionResult result = ingestionService.ingestOne(fileName, content);
        return Map.of(
                "success", true,
                "fileName", fileName,
                "succeeded", result.getSucceeded(),
                "failed", result.getFailed().stream()
                        .map(f -> Map.of("docName", f.docName(), "reason", f.reason()))
                        .toList(),
                "totalChunks", result.getTotalChunks(),
                "durationMs", result.getDurationMs()
        );
    }
}
