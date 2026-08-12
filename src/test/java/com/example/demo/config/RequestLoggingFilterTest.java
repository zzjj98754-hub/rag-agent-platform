package com.example.demo.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestLoggingFilterTest {

    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    @Test
    void shouldReuseValidatedUpstreamTraceIdAndClearMdc() throws Exception {
        RequestLoggingFilter filter = new RequestLoggingFilter();
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/chat");
        request.addHeader("X-Trace-Id", "upstream-trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, resp) -> {
            assertThat(MDC.get("traceId")).isEqualTo("upstream-trace-123");
        });

        assertThat(response.getHeader("X-Trace-Id"))
                .isEqualTo("upstream-trace-123");
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void shouldRejectUnsafeTraceIdAndGenerateNewOne() throws Exception {
        RequestLoggingFilter filter = new RequestLoggingFilter();
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/chat");
        request.addHeader("X-Trace-Id", "bad trace\nvalue");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, resp) -> {});

        assertThat(response.getHeader("X-Trace-Id"))
                .matches("[a-f0-9]{12}")
                .isNotEqualTo("bad trace\nvalue");
    }
}
