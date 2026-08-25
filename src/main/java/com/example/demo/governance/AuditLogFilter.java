package com.example.demo.governance;

import com.example.demo.security.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AuditLogFilter extends OncePerRequestFilter {
    private final AuditLogService audit;
    public AuditLogFilter(AuditLogService audit) { this.audit = audit; }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            if (isMutating(request.getMethod())) {
                Object principal = SecurityContextHolder.getContext().getAuthentication() == null
                        ? null : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
                Long userId = principal instanceof AuthenticatedUser user ? user.id() : null;
                try {
                    audit.record(userId, request.getMethod() + ' ' + request.getRequestURI(),
                            response.getStatus() < 400 ? "SUCCESS" : "FAILED",
                            System.currentTimeMillis() - start,
                            "HTTP", request.getRequestURI(), "{}",
                            request.getRemoteAddr(), MDC.get("traceId"));
                } catch (DataAccessException ignored) {
                    // Auditing must not make an otherwise healthy request unavailable.
                }
            }
        }
    }
    private boolean isMutating(String method) {
        return "POST".equals(method) || "PUT".equals(method)
                || "PATCH".equals(method) || "DELETE".equals(method);
    }
}
