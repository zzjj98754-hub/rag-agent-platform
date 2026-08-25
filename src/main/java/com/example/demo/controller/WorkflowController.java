package com.example.demo.controller;

import com.example.demo.security.CurrentUserProvider;
import com.example.demo.workflow.WorkflowDefinition;
import com.example.demo.workflow.WorkflowExecutor;
import com.example.demo.workflow.WorkflowRegistry;
import com.example.demo.workflow.WorkflowRun;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WorkflowController {
    private final WorkflowRegistry registry;
    private final WorkflowExecutor executor;
    private final CurrentUserProvider currentUser;

    public WorkflowController(WorkflowRegistry registry, WorkflowExecutor executor,
            CurrentUserProvider currentUser) {
        this.registry = registry;
        this.executor = executor;
        this.currentUser = currentUser;
    }

    @PostMapping("/admin/workflows")
    public WorkflowDefinition create(@RequestBody WorkflowDefinition definition) {
        return registry.save(definition, currentUser.requireCurrentUser().id());
    }

    @PutMapping("/admin/workflows/{code}")
    public WorkflowDefinition update(@PathVariable String code, @RequestBody WorkflowDefinition definition) {
        if (!code.equals(definition.code())) throw new IllegalArgumentException("Workflow code 不一致");
        return registry.save(definition, currentUser.requireCurrentUser().id());
    }

    @PostMapping("/workflows/{code}/run")
    public WorkflowRun run(@PathVariable String code, @RequestBody(required = false) Map<String, Object> input) {
        var user = currentUser.requireCurrentUser();
        return executor.run(
                code, input, user.role(), "workflow-" + user.id(), user.id());
    }

    @GetMapping("/workflows/runs/{id}")
    public WorkflowRun status(@PathVariable String id) {
        var user = currentUser.requireCurrentUser();
        return executor.getForUser(id, user.id(), user.role());
    }

    @PostMapping("/workflows/runs/{id}/cancel")
    public WorkflowRun cancel(@PathVariable String id) {
        var user = currentUser.requireCurrentUser();
        return executor.cancelForUser(id, user.id(), user.role());
    }

    @PostMapping("/workflows/runs/{id}/retry")
    public Map<String, Object> retry(@PathVariable String id) {
        var user = currentUser.requireCurrentUser();
        WorkflowRun run = executor.getForUser(id, user.id(), user.role());
        WorkflowRun restarted = executor.run(run.workflowCode(), run.input(),
                user.role(), "workflow-" + user.id(), user.id());
        return Map.of("previousRunId", id, "run", restarted);
    }
}
