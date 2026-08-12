package com.example.demo.dto;

import java.time.LocalDateTime;

public record DocumentView(
        Long id,
        String title,
        String filePath,
        String status,
        Long creatorId,
        LocalDateTime createTime,
        int chunkCount,
        int vectorCount,
        String embeddingSource,
        String embeddingStatus) {

    public DocumentView(
            Long id,
            String title,
            String filePath,
            String status,
            Long creatorId,
            LocalDateTime createTime,
            int chunkCount,
            String embeddingStatus) {
        this(
                id,
                title,
                filePath,
                status,
                creatorId,
                createTime,
                chunkCount,
                0,
                "unknown",
                embeddingStatus);
    }
}
