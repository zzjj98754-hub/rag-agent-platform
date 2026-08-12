package com.example.demo.service;

import com.example.demo.rag.SearchResult;
import java.util.List;

/**
 * 检索阶段与生成阶段之间的不可变数据契约。
 */
public record PreparedRagPrompt(
        String query,
        String sessionId,
        String prompt,
        List<SearchResult> documents) {

    public PreparedRagPrompt {
        documents = List.copyOf(documents);
    }
}
