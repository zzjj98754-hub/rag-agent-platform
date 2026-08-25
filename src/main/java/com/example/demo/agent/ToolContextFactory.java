package com.example.demo.agent;

import com.example.demo.security.AuthenticatedUser;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Creates the only supported security context for Spring AI tool callbacks. */
@Component
public class ToolContextFactory {
    public static final String USER_ROLE = "demo00.userRole";
    public static final String SESSION_ID = "demo00.sessionId";

    public Map<String, Object> create(AuthenticatedUser user, String sessionId) {
        return Map.of(
                USER_ROLE, user.role().name(),
                SESSION_ID, sessionId);
    }
}
