package com.example.demo.dto;

import java.util.List;

public record DocumentUploadResponse(
        boolean success,
        String fileName,
        List<String> indexed,
        List<String> skipped,
        List<IngestionResult.FailedDoc> failed,
        int totalChunks,
        long durationMs) {

    public static DocumentUploadResponse from(
            String fileName,
            IngestionResult result) {
        return new DocumentUploadResponse(
                result.getFailed().isEmpty(),
                fileName,
                result.getSucceeded(),
                result.getSkipped(),
                result.getFailed(),
                result.getTotalChunks(),
                result.getDurationMs());
    }
}
