package com.example.demo.governance;

import com.example.demo.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Base64;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/** Atomically reserves mutating requests and replays completed responses. */
@Component
public class IdempotencyInterceptor extends OncePerRequestFilter {
    private final IdempotencyService service;
    private final ObjectMapper mapper;
    public IdempotencyInterceptor(IdempotencyService service, ObjectMapper mapper) {
        this.service = service; this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Idempotency-Key");
        if (header == null || header.isBlank() || !isMutating(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String key = scopedKey(request, header);
        var reservation = service.reserve(key);
        if (reservation == IdempotencyService.Reservation.IN_PROGRESS) {
            response.setStatus(409);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"request_in_progress\"}");
            return;
        }
        if (reservation == IdempotencyService.Reservation.COMPLETED) {
            replay(service.get(key), response);
            return;
        }
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapper);
            if (wrapper.getStatus() >= 200 && wrapper.getStatus() < 300) {
                service.commit(key, mapper.writeValueAsString(new CachedResponse(
                        wrapper.getStatus(), wrapper.getContentType(),
                        Base64.getEncoder().encodeToString(wrapper.getContentAsByteArray()))));
            } else {
                service.release(key);
            }
        } catch (Exception ex) {
            service.release(key);
            throw ex;
        } finally {
            wrapper.copyBodyToResponse();
        }
    }

    private void replay(String encoded, HttpServletResponse response) throws IOException {
        if (encoded == null) {
            response.sendError(409, "Idempotency result unavailable");
            return;
        }
        CachedResponse cached = mapper.readValue(encoded, CachedResponse.class);
        response.setStatus(cached.status());
        if (cached.contentType() != null) response.setContentType(cached.contentType());
        response.getOutputStream().write(Base64.getDecoder().decode(cached.body()));
    }

    private String scopedKey(HttpServletRequest request, String key) {
        Object principal = SecurityContextHolder.getContext().getAuthentication() == null
                ? null : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        String user = principal instanceof AuthenticatedUser auth
                ? String.valueOf(auth.id()) : request.getRemoteAddr();
        return user + ':' + request.getMethod() + ':' + request.getRequestURI() + ':' + key;
    }

    private boolean isMutating(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
    }

    private record CachedResponse(int status, String contentType, String body) {}
}
