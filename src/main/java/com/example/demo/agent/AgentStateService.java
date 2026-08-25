package com.example.demo.agent;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class AgentStateService {
    private final Map<String, StateSnapshot> states = new ConcurrentHashMap<>();
    public StateSnapshot set(String sessionId, State state, String detail) {
        StateSnapshot snapshot = new StateSnapshot(sessionId, state, detail, Instant.now());
        states.put(sessionId, snapshot);
        return snapshot;
    }
    public StateSnapshot get(String sessionId) { return states.get(sessionId); }
    public enum State { IDLE, PLANNING, ACTING, COMPLETED, FAILED }
    public record StateSnapshot(String sessionId, State state, String detail, Instant updatedAt) {}
}
