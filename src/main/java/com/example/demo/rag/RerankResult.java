package com.example.demo.rag;

/**
 * 排序分数及其评分体系。不同体系的数值不可直接共用同一个阈值。
 */
public record RerankResult(double score, ScoreType scoreType) {

    public enum ScoreType {
        BGE,
        RRF
    }

    public RerankResult {
        scoreType = scoreType == null ? ScoreType.RRF : scoreType;
    }

    public static RerankResult bge(double score) {
        return new RerankResult(score, ScoreType.BGE);
    }

    public static RerankResult rrf(double score) {
        return new RerankResult(score, ScoreType.RRF);
    }
}
