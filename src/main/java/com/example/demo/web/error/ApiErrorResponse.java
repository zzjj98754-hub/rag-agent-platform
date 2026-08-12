package com.example.demo.web.error;

import java.time.Instant;
import java.util.Map;

/**
 * 所有 MVC 与 Spring Security 异常共用的稳定错误协议。
 */
public record ApiErrorResponse(
        Instant timestamp,
        String traceId,
        int status,
        String error,
        String code,
        String message,
        String path,
        Map<String, String> details) {

    public ApiErrorResponse {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
