package com.example.demo.controller;

import com.example.demo.agent.AgentPlan;
import com.example.demo.agent.AgentPlanner;
import com.example.demo.agent.AgentStateService;
import com.example.demo.security.CurrentUserProvider;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/agent")
public class AgentPlanningController {
    private final AgentPlanner planner;
    private final AgentStateService states;
    private final CurrentUserProvider currentUser;
    public AgentPlanningController(AgentPlanner planner, AgentStateService states,
            CurrentUserProvider currentUser) {
        this.planner = planner; this.states = states; this.currentUser = currentUser;
    }
    @PostMapping("/plan")
    public AgentPlan plan(@RequestBody Map<String, String> request) {
        var user = currentUser.requireCurrentUser();
        states.set("plan-" + user.id(), AgentStateService.State.PLANNING, request.get("query"));
        return planner.plan(request.get("query"));
    }
    @GetMapping("/state/{sessionId}")
    public AgentStateService.StateSnapshot state(@PathVariable String sessionId) {
        return states.get(sessionId);
    }
}
