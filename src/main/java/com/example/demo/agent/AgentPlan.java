package com.example.demo.agent;

import java.util.List;

public record AgentPlan(String planId, String query, List<Step> steps) {
    public AgentPlan { steps = steps == null ? List.of() : List.copyOf(steps); }
    public record Step(String id, String title, String expectedTool) {}
}
