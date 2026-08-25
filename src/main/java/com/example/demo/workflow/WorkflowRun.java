package com.example.demo.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record WorkflowRun(
        String id,
        String workflowCode,
        Status status,
        String currentNode,
        Map<String, Object> input,
        Map<String, Object> output,
        List<Step> steps,
        Instant startedAt,
        Instant finishedAt,
        String error) {
    public enum Status { RUNNING, RETRY_WAIT, WAITING_MANUAL, SUCCEEDED, FAILED, CANCELLED }
    public record Step(String nodeId, WorkflowNode.Type nodeType, Status status,
            Object input, Object output, int retryCount, String error) {}
}
