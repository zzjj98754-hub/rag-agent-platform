package com.example.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * OpenAI Compatible SSE 协议适配器，只负责请求模型与解析增量 token。
 */
@Service
@ConditionalOnProperty(
        name = "app.spring-ai.enabled",
        havingValue = "false",
        matchIfMissing = true)
public class OpenAiStreamingLlmClient implements StreamingLlmClient {

    private static final Logger log =
            LoggerFactory.getLogger(OpenAiStreamingLlmClient.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String url;
    private final String apiKey;
    private final String model;

    public OpenAiStreamingLlmClient(
            @Qualifier("streamingLlmWebClient") WebClient webClient,
            ObjectMapper objectMapper,
            @Value("${app.llm.streaming.url}") String url,
            @Value("${app.llm.streaming.api-key}") String apiKey,
            @Value("${app.llm.streaming.model}") String model) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.url = url;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public Flux<String> streamChat(String prompt) {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", prompt)),
                "stream", true);
        WebClient.RequestBodySpec request = webClient
                .post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM);
        if (!apiKey.isBlank()) {
            request.header(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer " + apiKey);
        }

        return request
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(String.class)
                .handle((data, sink) -> parseToken(data)
                        .ifPresent(sink::next))
                .cast(String.class)
                .doOnError(error -> {
                    log.error(
                            "流式 LLM 调用失败 | model={} message={}",
                            model,
                            error.getMessage());
                });
    }

    java.util.Optional<String> parseToken(String rawData) {
        if (rawData == null) {
            return java.util.Optional.empty();
        }
        String data = rawData.trim();
        if (data.startsWith("data:")) {
            data = data.substring("data:".length()).trim();
        }
        if (data.isEmpty() || "[DONE]".equals(data)) {
            return java.util.Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode deltaContent = root.path("choices")
                    .path(0)
                    .path("delta")
                    .path("content");
            if (deltaContent.isTextual()) {
                return java.util.Optional.of(deltaContent.asText());
            }
            JsonNode content = root.path("content");
            return content.isTextual()
                    ? java.util.Optional.of(content.asText())
                    : java.util.Optional.empty();
        } catch (Exception e) {
            log.warn("忽略无法解析的流式 LLM 事件: {}", data);
            return java.util.Optional.empty();
        }
    }

}
