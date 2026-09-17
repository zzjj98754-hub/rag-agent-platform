package com.example.demo.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AlibabaRagApprovalGraphServiceTest {
    @Test
    void invokesOfficialStateGraph() {
        var service = new AlibabaRagApprovalGraphService(null);
        var waiting = service.run("如何审批高风险检索？", false);
        assertEquals("WAITING_APPROVAL", waiting.get("status"));
        var result = service.approve((String) waiting.get("runId"));
        assertEquals("SUCCEEDED", result.get("status"));
        assertEquals("verify", result.get("currentNode"));
    }
}
