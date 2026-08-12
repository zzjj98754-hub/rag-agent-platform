package com.example.demo.web.error;

import java.time.Instant;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 错误响应构造器，只负责补齐状态、时间和 TraceId。
 */
@Component
public class ApiErrorFactory {

    public ApiErrorResponse create(
            HttpStatus status,
            String code,
            String message,
            String path) {
        return create(status, code, message, path, Map.of());
    }

    public ApiErrorResponse create(
            HttpStatus status,
            String code,
            String message,
            String path,
            Map<String, String> details) {
        String traceId = MDC.get("traceId");
        return new ApiErrorResponse(
                Instant.now(),
                traceId == null ? "" : traceId,
                status.value(),
                status.getReasonPhrase(),
                code,
                message,
                path,
                details);
    }
}
