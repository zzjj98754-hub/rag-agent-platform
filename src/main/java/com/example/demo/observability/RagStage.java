package com.example.demo.observability;

public enum RagStage {
    BM25("rag.bm25.duration"),
    EMBEDDING("rag.embedding.duration"),
    RETRIEVAL("rag.retrieval.duration"),
    RERANK("rag.rerank.duration"),
    LLM("rag.llm.duration"),
    TOTAL("rag.request.duration");

    private final String metricName;

    RagStage(String metricName) {
        this.metricName = metricName;
    }

    public String metricName() {
        return metricName;
    }
}
