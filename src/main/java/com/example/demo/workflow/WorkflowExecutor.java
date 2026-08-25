package com.example.demo.workflow;

import com.example.demo.security.UserRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class WorkflowExecutor {
    private final WorkflowRegistry registry;
    private final WorkflowPersistenceService persistence;
    private final Map<WorkflowNode.Type, WorkflowNodeHandler> handlers;
    private final WorkflowRetryPolicy retryPolicy;
    private final Map<String, WorkflowRun> runs = new ConcurrentHashMap<>();

    public WorkflowExecutor(
            WorkflowRegistry registry,
            WorkflowPersistenceService persistence,
            List<WorkflowNodeHandler> handlers,
            WorkflowRetryPolicy retryPolicy) {
        this.registry = registry;
        this.persistence = persistence;
        this.handlers = handlers.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                WorkflowNodeHandler::type, handler -> handler));
        this.retryPolicy = retryPolicy;
    }

    public WorkflowRun run(String code, Map<String, Object> input, UserRole role, String sessionId) {
        return run(code, input, role, sessionId, -1L);
    }

    public WorkflowRun run(
            String code,
            Map<String, Object> input,
            UserRole role,
            String sessionId,
            long ownerId) {
        WorkflowDefinition definition = registry.get(code);
        String id = UUID.randomUUID().toString().replace("-", "");
        WorkflowRun initial = new WorkflowRun(id, code, WorkflowRun.Status.RUNNING, null,
                input == null ? Map.of() : Map.copyOf(input), Map.of(), List.of(), Instant.now(), null, null);
        runs.put(id, initial);
        persistence.createRun(initial, ownerId, sessionId);
        CompletableFuture.runAsync(() -> execute(
                id, definition, initial.input(), role, sessionId, ownerId));
        return initial;
    }

    public WorkflowRun get(String id) {
        return persistence.getRun(id);
    }

    public WorkflowRun getForUser(String id, long userId, UserRole role) {
        requireOwner(id, userId, role);
        return get(id);
    }

    public WorkflowRun cancel(String id) {
        WorkflowRun current = get(id);
        WorkflowRun cancelled = new WorkflowRun(current.id(), current.workflowCode(),
                WorkflowRun.Status.CANCELLED, current.currentNode(), current.input(), current.output(),
                current.steps(), current.startedAt(), Instant.now(), "cancelled");
        save(cancelled);
        return cancelled;
    }

    public WorkflowRun cancelForUser(String id, long userId, UserRole role) {
        requireOwner(id, userId, role);
        return cancel(id);
    }

    private void execute(String id, WorkflowDefinition definition, Map<String, Object> input,
            UserRole role, String sessionId, long ownerId) {
        WorkflowRun persisted = get(id);
        List<WorkflowRun.Step> steps = new ArrayList<>(persisted.steps());
        Map<String, Object> values = new LinkedHashMap<>(input);
        values.putAll(persisted.output());
        java.util.Set<String> completed = steps.stream()
                .filter(step -> step.status() == WorkflowRun.Status.SUCCEEDED)
                .map(WorkflowRun.Step::nodeId)
                .collect(java.util.stream.Collectors.toSet());
        try {
            for (WorkflowNode node : topological(definition.nodes())) {
                if (completed.contains(node.id())) continue;
                WorkflowRun current = get(id);
                if (current.status() == WorkflowRun.Status.CANCELLED) return;
                Object nodeInput = values.getOrDefault(node.id(), values);
                try {
                    var outcome = retryPolicy.execute(node, () -> executeNode(
                            node, nodeInput, values, role, sessionId, ownerId));
                    Object output = outcome.value();
                    values.put(node.id(), output);
                    steps.add(new WorkflowRun.Step(node.id(), node.type(),
                            WorkflowRun.Status.SUCCEEDED, nodeInput, output,
                            outcome.retryCount(), null));
                    WorkflowRun updated = new WorkflowRun(id, definition.code(), WorkflowRun.Status.RUNNING,
                            node.id(), input, Map.copyOf(values), List.copyOf(steps), current.startedAt(), null, null);
                    persistence.saveStep(id, steps.get(steps.size() - 1));
                    save(updated);
                } catch (Exception ex) {
                    int retryCount = ex instanceof WorkflowRetryPolicy.RetryExhaustedException exhausted
                            ? exhausted.retryCount() : 0;
                    steps.add(new WorkflowRun.Step(node.id(), node.type(), WorkflowRun.Status.FAILED,
                            nodeInput, null, retryCount, ex.getMessage()));
                    WorkflowRun failed = new WorkflowRun(id, definition.code(), WorkflowRun.Status.FAILED,
                            node.id(), input, Map.copyOf(values), List.copyOf(steps), current.startedAt(), Instant.now(), ex.getMessage());
                    persistence.saveStep(id, steps.get(steps.size() - 1));
                    save(failed);
                    return;
                }
            }
            WorkflowRun current = get(id);
            save(new WorkflowRun(id, definition.code(), WorkflowRun.Status.SUCCEEDED,
                    current.currentNode(), input, Map.copyOf(values), List.copyOf(steps),
                    current.startedAt(), Instant.now(), null));
        } catch (Exception ex) {
            WorkflowRun current = get(id);
            save(new WorkflowRun(id, definition.code(), WorkflowRun.Status.FAILED,
                    current.currentNode(), input, Map.copyOf(values), List.copyOf(steps),
                    current.startedAt(), Instant.now(), ex.getMessage()));
        }
    }

    public void recover(String id) {
        WorkflowRun run = get(id);
        WorkflowDefinition definition = registry.get(run.workflowCode());
        long ownerId = persistence.ownerId(id);
        String sessionId = persistence.sessionId(id);
        CompletableFuture.runAsync(() -> execute(
                id, definition, run.input(), UserRole.USER, sessionId, ownerId));
    }

    private void save(WorkflowRun run) {
        runs.put(run.id(), run);
        persistence.updateRun(run);
    }

    private void requireOwner(String id, long userId, UserRole role) {
        if (role != UserRole.ADMIN && persistence.ownerId(id) != userId) {
            throw new AccessDeniedException("No access to Workflow run");
        }
    }

    private Object executeNode(
            WorkflowNode node,
            Object input,
            Map<String, Object> values,
            UserRole role,
            String sessionId,
            long ownerId) {
        WorkflowNodeHandler handler = handlers.get(node.type());
        if (handler == null) {
            throw new IllegalArgumentException("Unsupported Workflow node: " + node.type());
        }
        return handler.execute(node, new WorkflowNodeContext(
                input, values, role, sessionId, ownerId));
    }

    private List<WorkflowNode> topological(List<WorkflowNode> nodes) {
        List<WorkflowNode> result = new ArrayList<>();
        List<WorkflowNode> remaining = new ArrayList<>(nodes);
        while (!remaining.isEmpty()) {
            WorkflowNode next = remaining.stream().filter(node -> result.stream()
                    .map(WorkflowNode::id).toList().containsAll(node.dependsOn())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Workflow 循环依赖"));
            remaining.remove(next);
            result.add(next);
        }
        return result;
    }
}
