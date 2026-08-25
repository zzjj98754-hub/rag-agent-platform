package com.example.demo.workflow;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowDslValidatorTest {
    private final WorkflowDslValidator validator = new WorkflowDslValidator(16);

    @Test
    void shouldAcceptDagAndRejectCycles() {
        assertThatCode(() -> validator.validate(new WorkflowDefinition(
                "weekly-report", 1, true, List.of(
                        new WorkflowNode("collect", WorkflowNode.Type.SKILL, List.of(), Map.of()),
                        new WorkflowNode("write", WorkflowNode.Type.LLM,
                                List.of("collect"), Map.of())))))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> validator.validate(new WorkflowDefinition(
                "cycle", 1, true, List.of(
                        new WorkflowNode("a", WorkflowNode.Type.LLM, List.of("b"), Map.of()),
                        new WorkflowNode("b", WorkflowNode.Type.LLM, List.of("a"), Map.of())))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retryingSideEffectToolRequiresIdempotencyKey() {
        assertThatThrownBy(() -> validator.validate(new WorkflowDefinition(
                "unsafe", 1, true, List.of(new WorkflowNode(
                        "send", WorkflowNode.Type.TOOL, List.of(),
                        Map.of("tool", "send", "maxRetries", 2))))))
                .hasMessageContaining("idempotencyKey");
    }
}
