package com.example.demo.governance;

import com.example.demo.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class QuotaInterceptor implements HandlerInterceptor {
    private final QuotaService quota;
    public QuotaInterceptor(QuotaService quota) { this.quota = quota; }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Object principal = SecurityContextHolder.getContext().getAuthentication() == null
                ? null : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof AuthenticatedUser user) {
            long estimatedTokens = Math.max(1, request.getContentLengthLong() / 4);
            if (!quota.tryConsume(String.valueOf(user.id()), estimatedTokens)) {
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS, "Monthly token quota exceeded");
            }
        }
        return true;
    }
}
