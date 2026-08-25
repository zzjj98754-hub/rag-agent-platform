package com.example.demo.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Deterministic baseline planner; a Spring AI planner can replace this strategy later. */
@Service
public class AgentPlanner {
    public AgentPlan plan(String query) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query 不能为空");
        List<AgentPlan.Step> steps = new ArrayList<>();
        String normalized = query.trim();
        if (normalized.contains("并") || normalized.contains("然后") || normalized.contains("汇总")) {
            steps.add(new AgentPlan.Step("step-1", "收集问题所需数据", "knowledge_search"));
            steps.add(new AgentPlan.Step("step-2", "整理并校验工具结果", "calculator"));
            steps.add(new AgentPlan.Step("step-3", "生成最终汇总", null));
        } else {
            steps.add(new AgentPlan.Step("step-1", "分析问题并决定是否调用工具", null));
        }
        return new AgentPlan(UUID.randomUUID().toString().replace("-", ""), normalized, steps);
    }
}
