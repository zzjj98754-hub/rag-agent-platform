package com.example.demo.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Resumes durable runs left active by a process restart. */
@Component
public class WorkflowRecoveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(WorkflowRecoveryScheduler.class);
    private final WorkflowPersistenceService persistence;
    private final WorkflowExecutor executor;

    public WorkflowRecoveryScheduler(
            WorkflowPersistenceService persistence, WorkflowExecutor executor) {
        this.persistence = persistence;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        for (String runId : persistence.recoverableRunIds()) {
            try {
                executor.recover(runId);
            } catch (RuntimeException ex) {
                log.error("Workflow recovery failed | run={} error={}",
                        runId, ex.getMessage());
            }
        }
    }
}
