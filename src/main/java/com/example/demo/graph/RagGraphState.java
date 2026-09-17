package com.example.demo.graph;

import java.util.List;
import java.util.Map;

/** Explicit state makes every graph transition inspectable and checkpointable. */
public record RagGraphState(String runId, String query, String status, String currentNode,
        boolean highRisk, String plan, List<Map<String, Object>> retrieval, String toolResult,
        String verification, List<String> records) { }
