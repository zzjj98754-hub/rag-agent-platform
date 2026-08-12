package com.example.demo.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent Loop 对外结果。工具轨迹用于审计，但不包含模型内部推理文本。
 */
public record AgentResult(
        String sessionId,
        String answer,
        Status status,
        int steps,
        List<ToolTrace> toolHistory) {

    public AgentResult {
        toolHistory = toolHistory == null ? List.of() : List.copyOf(toolHistory);
    }

    public enum Status {
        COMPLETED,
        MAX_STEPS,
        LOOP_DETECTED,
        FAILED
    }

    public record ToolTrace(
            String toolCallId,
            String toolName,
            Map<String, Object> arguments,
            boolean success,
            boolean denied,
            String observation) {

        public ToolTrace {
            arguments = arguments == null || arguments.isEmpty()
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        }
    }
}
