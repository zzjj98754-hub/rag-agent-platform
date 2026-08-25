package com.example.demo.service;

import java.util.List;

/** Compatibility view retained for callers that have not moved to EmbeddingModel yet. */
public class SpringAiEmbeddingModel {
    private final ResilientEmbeddingService delegate;
    public SpringAiEmbeddingModel(ResilientEmbeddingService delegate) { this.delegate = delegate; }
    public float[] embed(String text) { return delegate.embed(text); }
    public List<float[]> embed(List<String> texts) { return delegate.embedBatch(texts); }
    public int dimensions() { return delegate.dimension(); }
    public String modelName() { return delegate.modelName(); }
}
