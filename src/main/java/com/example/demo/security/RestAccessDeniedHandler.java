package com.example.demo.security;

import com.example.demo.web.error.ApiErrorWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ApiErrorWriter errorWriter;

    public RestAccessDeniedHandler(ApiErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException {
        errorWriter.write(
                response,
                HttpStatus.FORBIDDEN,
                "ACCESS_DENIED",
                "没有权限访问该资源",
                request.getRequestURI());
    }
}
