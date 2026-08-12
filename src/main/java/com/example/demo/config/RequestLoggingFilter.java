package com.example.demo.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * 请求日志 Filter —— 全链路 TraceId + 请求计时 + 异常记录。
 *
 * 面试要点：
 * - MDC 基于 ThreadLocal，同一线程内所有 log 自动携带 traceId，无需显式传参
 * - TraceId 生成放在 Filter 最外层，保证 doFilter 前后都能取到
 * - finally 块清理 MDC，防止线程复用时污染下次请求
 * - 响应头回传 TraceId，前端报错时带上，可快速关联后端日志
 */
public class RequestLoggingFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final String TRACE_HEADER = "X-Trace-Id";
    private static final String VALID_TRACE_ID = "^[A-Za-z0-9_-]{8,64}$";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        // 1. 生成短 TraceId
        String traceId = resolveTraceId(req);
        MDC.put("traceId", traceId);
        resp.setHeader(TRACE_HEADER, traceId);

        long start = System.currentTimeMillis();
        String method = req.getMethod();
        String uri = req.getRequestURI();
        String query = req.getQueryString();

        // 2. 请求开始日志
        log.info("→ {} {} {}", method, uri, query != null ? "?" + query : "");

        try {
            chain.doFilter(request, response);

            long elapsed = System.currentTimeMillis() - start;
            int status = resp.getStatus();

            // 3. 请求完成日志（按状态码分级）
            if (status >= 500) {
                log.error("← {} {} → {} {}ms [SERVER ERROR]", method, uri, status, elapsed);
            } else if (status >= 400) {
                log.warn("← {} {} → {} {}ms [CLIENT ERROR]", method, uri, status, elapsed);
            } else {
                log.info("← {} {} → {} {}ms", method, uri, status, elapsed);
            }

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("← {} {} → EXCEPTION {}ms | {}: {}",
                    method, uri, elapsed, e.getClass().getSimpleName(), e.getMessage());
            throw e;

        } finally {
            // 4. 清理 MDC：线程池复用场景下防止 traceId 串到其他请求
            MDC.clear();
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String upstreamTraceId = request.getHeader(TRACE_HEADER);
        if (upstreamTraceId != null && upstreamTraceId.matches(VALID_TRACE_ID)) {
            return upstreamTraceId;
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

}
