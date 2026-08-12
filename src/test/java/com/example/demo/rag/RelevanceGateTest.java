package com.example.demo.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RelevanceGateTest {

    private final RelevanceGate gate = new RelevanceGate(0.35, 0.01);

    @Test
    void rrfFallbackUsesRrfThresholdInsteadOfBgeThreshold() {
        SearchResult rrfResult = new SearchResult(
                "redis.txt:0:0",
                "Redis child",
                0.032,
                "redis.txt:0",
                "Redis parent");

        RelevanceGate.GateDecision decision =
                gate.evaluate(List.of(rrfResult));

        assertThat(decision.passed()).isTrue();
        assertThat(decision.scoreType())
                .isEqualTo(RerankResult.ScoreType.RRF);
        assertThat(decision.effectiveDocs()).containsExactly(rrfResult);
    }

    @Test
    void bgeResultStillUsesStricterSemanticThreshold() {
        SearchResult bgeResult = new SearchResult(
                "redis.txt:0:0",
                "Redis child",
                RerankResult.bge(0.20),
                "redis.txt:0",
                "Redis parent");

        RelevanceGate.GateDecision decision =
                gate.evaluate(List.of(bgeResult));

        assertThat(decision.passed()).isFalse();
        assertThat(decision.scoreType())
                .isEqualTo(RerankResult.ScoreType.BGE);
        assertThat(decision.effectiveDocs()).isEmpty();
    }
}
