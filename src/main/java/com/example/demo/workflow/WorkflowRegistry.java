package com.example.demo.workflow;

import java.util.List;
import org.springframework.stereotype.Service;

/** DB-backed immutable workflow definition registry. */
@Service
public class WorkflowRegistry {
    private final WorkflowDslValidator validator;
    private final WorkflowPersistenceService persistence;

    public WorkflowRegistry(
            WorkflowDslValidator validator,
            WorkflowPersistenceService persistence) {
        this.validator = validator;
        this.persistence = persistence;
    }

    public WorkflowDefinition save(WorkflowDefinition definition) {
        return save(definition, null);
    }

    public WorkflowDefinition save(WorkflowDefinition definition, Long ownerId) {
        validator.validate(definition);
        return persistence.saveDefinition(definition, ownerId);
    }

    public WorkflowDefinition get(String code) {
        return persistence.getDefinition(code);
    }

    public List<WorkflowDefinition> list() {
        return persistence.listDefinitions();
    }
}
