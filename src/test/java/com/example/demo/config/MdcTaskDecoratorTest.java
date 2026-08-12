package com.example.demo.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class MdcTaskDecoratorTest {

    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    @Test
    void shouldPropagateAndCleanTraceId() {
        MDC.put("traceId", "trace-123456");
        AtomicReference<String> observed = new AtomicReference<>();
        Runnable decorated = new MdcTaskDecorator().decorate(
                () -> observed.set(MDC.get("traceId")));
        MDC.clear();

        decorated.run();

        assertThat(observed).hasValue("trace-123456");
        assertThat(MDC.get("traceId")).isNull();
    }
}
