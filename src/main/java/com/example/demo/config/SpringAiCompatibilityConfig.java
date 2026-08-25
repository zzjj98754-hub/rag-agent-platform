package com.example.demo.config;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Central migration boundary for Spring AI. The application keeps its stable
 * domain contracts while the provider-specific ChatClient/EmbeddingModel
 * beans are configured by the Spring AI starter.
 */
@Configuration
public class SpringAiCompatibilityConfig {
    @Bean
    public SpringAiRuntimeProperties springAiRuntimeProperties(
            @Value("${app.spring-ai.enabled}") boolean enabled) {
        return new SpringAiRuntimeProperties(enabled);
    }

    public record SpringAiRuntimeProperties(boolean enabled) {
        public Map<String, Object> asMap() {
            return Map.of("enabled", enabled, "provider", "openai-compatible");
        }
    }
}
