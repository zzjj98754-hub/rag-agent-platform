package com.example.demo.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 入库结果 —— 记录本次入库操作的成功/跳过/失败明细。
 */
public class IngestionResult {

    private final List<String> succeeded;
    private final List<String> skipped;
    private final List<FailedDoc> failed;
    private final int totalChunks;
    private final long durationMs;

    public record FailedDoc(String docName, String reason) {}

    public IngestionResult(List<String> succeeded, List<String> skipped,
                           List<FailedDoc> failed, int totalChunks, long durationMs) {
        this.succeeded = List.copyOf(succeeded);
        this.skipped = List.copyOf(skipped);
        this.failed = List.copyOf(failed);
        this.totalChunks = totalChunks;
        this.durationMs = durationMs;
    }

    public static IngestionResult empty() {
        return new IngestionResult(List.of(), List.of(), List.of(), 0, 0);
    }

    /** 合并两个结果（用于多文档批量入库） */
    public IngestionResult merge(IngestionResult other) {
        List<String> mergedSucceeded = new ArrayList<>(this.succeeded);
        mergedSucceeded.addAll(other.succeeded);
        List<String> mergedSkipped = new ArrayList<>(this.skipped);
        mergedSkipped.addAll(other.skipped);
        List<FailedDoc> mergedFailed = new ArrayList<>(this.failed);
        mergedFailed.addAll(other.failed);
        return new IngestionResult(mergedSucceeded, mergedSkipped, mergedFailed,
                this.totalChunks + other.totalChunks, this.durationMs + other.durationMs);
    }

    // ---- getters ----

    public List<String> getSucceeded() { return succeeded; }
    public List<String> getSkipped() { return skipped; }
    public List<FailedDoc> getFailed() { return failed; }
    public int getTotalChunks() { return totalChunks; }
    public long getDurationMs() { return durationMs; }
    public int totalFiles() { return succeeded.size() + skipped.size() + failed.size(); }
}
