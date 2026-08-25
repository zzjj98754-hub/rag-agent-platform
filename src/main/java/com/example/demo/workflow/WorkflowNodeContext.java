package com.example.demo.workflow;

import com.example.demo.security.UserRole;
import java.util.Map;

public record WorkflowNodeContext(
        Object input,
        Map<String, Object> values,
        UserRole role,
        String sessionId,
        long ownerId) {
    public WorkflowNodeContext {
        values = values == null ? Map.of() : Map.copyOf(values);
    }
}
