package com.example.demo.controller;

import com.example.demo.graph.RagApprovalGraphService;
import com.example.demo.graph.RagGraphState;
import com.example.demo.security.CurrentUserProvider;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.Optional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Ready-to-demo fixed graph; custom DSL workflows remain available under /workflows. */
@RestController @RequestMapping("/graph/rag") @Validated
public class RagGraphController {
    private final RagApprovalGraphService graph; private final Optional<com.example.demo.graph.AlibabaRagApprovalGraphService> official; private final CurrentUserProvider user;
    public RagGraphController(RagApprovalGraphService graph, Optional<com.example.demo.graph.AlibabaRagApprovalGraphService> official, CurrentUserProvider user) { this.graph=graph; this.official=official; this.user=user; }
    @PostMapping("/runs") public RagGraphState start(@RequestBody Map<String,Object> body) {
        var u=user.requireCurrentUser(); String query=String.valueOf(body.get("query")); if (query.isBlank()) throw new IllegalArgumentException("query 不能为空");
        boolean highRisk = Boolean.TRUE.equals(body.get("highRisk"));
        return official.map(o -> o.start(query, highRisk, u.id(), u.role()))
                .orElseGet(() -> graph.start(query, highRisk, u.id(), u.role())); }
    @GetMapping("/runs/{id}") public RagGraphState get(@PathVariable String id) { var u=user.requireCurrentUser(); return official.map(o -> graphState(o.state(id), id)).orElseGet(() -> graph.get(id,u.id(),u.role())); }
    @GetMapping("/approvals") public Map<String,Object> approvals() { var u=user.requireCurrentUser(); return Map.of("items",graph.pending(u.id(),u.role())); }
    @PostMapping("/runs/{id}/approval") public RagGraphState approve(@PathVariable String id,@RequestBody Map<String,Boolean> body) { var u=user.requireCurrentUser(); boolean approved=Boolean.TRUE.equals(body.get("approved")); return official.map(o -> o.approveState(id, approved, u.id(), u.role())).orElseGet(() -> graph.approve(id,approved,u.id(),u.role())); }
    private RagGraphState graphState(Map<String,Object> state, String id) { return new RagGraphState(id, String.valueOf(state.getOrDefault("query", "")), String.valueOf(state.getOrDefault("status", "RUNNING")), String.valueOf(state.getOrDefault("currentNode", "plan")), false, "官方 Spring AI Alibaba Graph 审批流程", java.util.List.of(), null, null, java.util.List.of("official_state_graph")); }
}
