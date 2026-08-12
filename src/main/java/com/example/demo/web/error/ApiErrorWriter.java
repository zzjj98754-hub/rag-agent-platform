package com.example.demo.web.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Security Filter 链错误的 JSON 输出适配器。
 */
@Component
public class ApiErrorWriter {

    private final ObjectMapper objectMapper;
    private final ApiErrorFactory errorFactory;

    public ApiErrorWriter(
            ObjectMapper objectMapper,
            ApiErrorFactory errorFactory) {
        this.objectMapper = objectMapper;
        this.errorFactory = errorFactory;
    }

    public void write(
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String message,
            String path) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(
                response.getOutputStream(),
                errorFactory.create(status, code, message, path));
    }
}
