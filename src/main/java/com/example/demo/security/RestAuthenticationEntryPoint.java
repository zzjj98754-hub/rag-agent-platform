package com.example.demo.security;

import com.example.demo.web.error.ApiErrorWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiErrorWriter errorWriter;

    public RestAuthenticationEntryPoint(ApiErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {
        errorWriter.write(
                response,
                HttpStatus.UNAUTHORIZED,
                "AUTHENTICATION_FAILED",
                "认证失败或 Token 无效",
                request.getRequestURI());
    }
}
