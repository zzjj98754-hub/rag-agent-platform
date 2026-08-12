package com.example.demo.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

class CorsConfigTest {

    @Test
    void shouldAllowConfiguredFrontendOriginsAndSseHeaders() {
        CorsConfigurationSource source =
                new CorsConfig().corsConfigurationSource(
                        "http://localhost:5173, http://127.0.0.1:5173");

        CorsConfiguration configuration =
                source.getCorsConfiguration(
                        new MockHttpServletRequest("GET", "/chat/stream"));

        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins()).containsExactly(
                "http://localhost:5173",
                "http://127.0.0.1:5173");
        assertThat(configuration.getAllowedMethods())
                .contains("GET", "POST", "DELETE", "OPTIONS");
        assertThat(configuration.getAllowedHeaders())
                .contains("Authorization", "Content-Type", "X-Trace-Id");
        assertThat(configuration.getExposedHeaders())
                .isEqualTo(List.of("X-Trace-Id"));
        assertThat(configuration.getAllowCredentials()).isTrue();
    }
}
