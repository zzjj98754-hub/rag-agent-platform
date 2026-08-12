package com.example.demo.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * 模拟外部 LLM API 端点 —— 用于演示 HTTP 超时处理。
 *
 * 面试要点：
 * - 通过 delay 参数模拟慢服务，验证客户端超时配置是否生效
 * - 返回标准 JSON 格式：{ "content": "...", "model": "...", "tokens": N }
 * - 真实场景替换为 OpenAI / Anthropic / 阿里通义等 API 调用
 */
@RestController
@Profile({"dev", "loadtest"})
public class MockExternalLlmController {

    private static final Logger log = LoggerFactory.getLogger(MockExternalLlmController.class);
    private static final String AGENT_PROTOCOL_MARKER = "AGENT_DECISION_PROTOCOL_V1";
    private static final Pattern ARITHMETIC_PATTERN =
            Pattern.compile("[0-9()\\s+\\-*/.%]+");

    private final ObjectMapper objectMapper;
    private final long minDelayMillis;
    private final long maxDelayMillis;
    private final long streamTokenDelayMillis;
    private final String defaultModel;

    public MockExternalLlmController(
            ObjectMapper objectMapper,
            @Value("${app.mock-llm.min-delay-ms}")
            long minDelayMillis,
            @Value("${app.mock-llm.max-delay-ms}")
            long maxDelayMillis,
            @Value("${app.mock-llm.stream-token-delay-ms}")
            long streamTokenDelayMillis,
            @Value("${app.llm.model}") String defaultModel) {
        this.objectMapper = objectMapper;
        this.minDelayMillis = minDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
        this.streamTokenDelayMillis = streamTokenDelayMillis;
        this.defaultModel = defaultModel;
    }

    /**
     * 模拟 LLM API。
     *
     * 请求体：{ "prompt": "...", "model": "...", "delay": 可选(ms) }
     * 响应体：{ "content": "...", "model": "...", "tokens": N }
     */
    @PostMapping("/mock-llm")
    public Map<String, Object> mockLlm(@RequestBody Map<String, Object> request) {
        String prompt = (String) request.getOrDefault("prompt", "");
        String model = (String) request.getOrDefault(
                "model",
                defaultModel);

        // 可选延迟参数：模拟慢 API（毫秒）
        Object delayObj = request.get("delay");
        long delay = delayObj instanceof Number ? ((Number) delayObj).longValue() : randomDelay();
        if (delay > 0) {
            try {
                log.info("模拟 LLM 处理延迟 | delay={}ms", delay);
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        String content = generateMockResponse(prompt);
        log.info("模拟 LLM 返回 | 长度={} | 延迟={}ms", content.length(), delay);

        return Map.of(
                "content", content,
                "model", model,
                "tokens", content.length()
        );
    }

    /** 随机 100-200ms 延迟，模拟真实 API 响应时间 */
    private long randomDelay() {
        if (minDelayMillis >= maxDelayMillis) {
            return minDelayMillis;
        }
        return ThreadLocalRandom.current().nextLong(
                minDelayMillis,
                maxDelayMillis + 1);
    }

    /**
     * 本地开发用的 OpenAI Compatible SSE 上游。
     */
    @PostMapping(
            value = "/mock-llm/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> mockStreamingLlm(
            @RequestBody Map<String, Object> request) {
        String prompt = extractOpenAiPrompt(request);
        String content = generateMockResponse(prompt);
        return Flux.fromStream(content.codePoints()
                        .mapToObj(codePoint -> new String(
                                Character.toChars(codePoint))))
                .map(this::toStreamingEvent)
                .delayElements(Duration.ofMillis(
                        streamTokenDelayMillis))
                .concatWithValues(ServerSentEvent
                        .builder("[DONE]")
                        .build());
    }

    private ServerSentEvent<String> toStreamingEvent(String token) {
        String data = toJson(Map.of(
                "choices", java.util.List.of(Map.of(
                        "delta", Map.of("content", token)))));
        return ServerSentEvent.builder(data).build();
    }

    private String extractOpenAiPrompt(Map<String, Object> request) {
        Object messagesValue = request.get("messages");
        if (!(messagesValue instanceof java.util.List<?> messages)
                || messages.isEmpty()) {
            return "";
        }
        Object lastMessage = messages.get(messages.size() - 1);
        if (!(lastMessage instanceof Map<?, ?> message)) {
            return "";
        }
        Object content = message.get("content");
        return content == null ? "" : String.valueOf(content);
    }

    private String generateMockResponse(String prompt) {
        if (prompt.contains(AGENT_PROTOCOL_MARKER)) {
            return generateAgentDecision(prompt);
        }

        StringBuilder answer = new StringBuilder();
        answer.append("【模拟 LLM 回答】\n\n");

        if (prompt.contains("缓存穿透")) {
            answer.append("缓存穿透是指：查询一个数据库中也不存在的数据，每次请求都绕过缓存直接打到数据库。\n");
            answer.append("常见解决方案包括：\n");
            answer.append("1. 布隆过滤器：在缓存之前加一层过滤，判断 key 是否存在\n");
            answer.append("2. 缓存空值：对于查询不到的数据，也缓存一个空值或标记，防止反复穿透\n");
        } else if (prompt.contains("缓存雪崩")) {
            answer.append("缓存雪崩是指：大量缓存 key 在同一时间过期，导致所有请求同时打到数据库。\n");
            answer.append("常见解决方案：\n");
            answer.append("1. TTL 加随机抖动，避免同时过期\n");
            answer.append("2. 多级缓存架构，本地缓存兜底\n");
            answer.append("3. 限流降级，保护下游服务\n");
        } else if (prompt.contains("超时")) {
            answer.append("HTTP 超时是网络可靠性的基石。\n");
            answer.append("连接超时保证快速失败，不被不可达的目标阻塞；\n");
            answer.append("读取超时防止已建连后服务端 hang 住占用线程资源。\n");
            answer.append("生产环境应针对不同下游配置差异化的超时值。\n");
        } else if (prompt.contains("IoC") || prompt.contains("控制反转")) {
            answer.append("IoC（控制反转）是 Spring 框架的核心思想。\n");
            answer.append("它把对象的创建和管理权从程序员手中交给了 Spring 容器，开发者只需声明依赖即可。\n");
            answer.append("主要实现方式是依赖注入（DI）：通过构造器、Setter 或字段注入把依赖传进来。\n");
        } else if (prompt.contains("多线程")) {
            answer.append("Java 多线程是并发编程的核心，主要要点：\n");
            answer.append("1. 线程创建方式：Thread、Runnable、Callable、线程池\n");
            answer.append("2. synchronized 和 Lock 的对比需要掌握\n");
            answer.append("3. 线程池的核心参数：核心线程数、最大线程数、存活时间、工作队列、拒绝策略\n");
        } else if (!prompt.contains("没有找到相关文档")) {
            answer.append("根据提供的文档内容，已找到相关信息。\n");
            answer.append("（本回复来自模拟 LLM 端点，实际场景会调用 OpenAI/Anthropic 等真实 API。）\n");
        } else {
            answer.append("该问题暂时超出我的知识范围，建议补充相关文档或尝试更具体的提问。\n");
        }

        return answer.toString();
    }

    /**
     * 本地演示用结构化决策。真实环境由外部模型按相同协议完成判断。
     */
    private String generateAgentDecision(String prompt) {
        String question = extractSection(
                prompt,
                "CURRENT_QUESTION:\n",
                "\nCONVERSATION:");
        String observation = extractSection(
                prompt,
                "LATEST_OBSERVATION:\n",
                "\nAVAILABLE_TOOLS:");

        if (!observation.isBlank() && !"(none)".equals(observation)) {
            return toJson(Map.of(
                    "type", "final_answer",
                    "answer", "根据工具执行结果：" + observation));
        }

        String expression = findArithmeticExpression(question);
        if (expression != null) {
            return toJson(Map.of(
                    "type", "tool_call",
                    "toolName", "calculator",
                    "arguments", Map.of("expression", expression)));
        }

        if (isKnowledgeQuestion(question)) {
            return toJson(Map.of(
                    "type", "tool_call",
                    "toolName", "search_knowledge",
                    "arguments", Map.of("query", question)));
        }

        return toJson(Map.of(
                "type", "final_answer",
                "answer", "这是本地 Mock Agent 的直接回答。接入真实 LLM 后会由模型动态选择工具。"));
    }

    private String findArithmeticExpression(String question) {
        Matcher matcher = ARITHMETIC_PATTERN.matcher(question);
        while (matcher.find()) {
            String candidate = matcher.group().trim();
            if (candidate.matches(".*\\d.*")
                    && candidate.matches(".*[+\\-*/%].*")) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isKnowledgeQuestion(String question) {
        return question.contains("Java")
                || question.contains("Spring")
                || question.contains("Redis")
                || question.contains("MySQL")
                || question.contains("RAG")
                || question.contains("缓存")
                || question.contains("线程")
                || question.contains("向量")
                || question.contains("检索");
    }

    private String extractSection(String prompt, String startMarker, String endMarker) {
        int start = prompt.indexOf(startMarker);
        if (start < 0) {
            return "";
        }
        start += startMarker.length();
        int end = prompt.indexOf(endMarker, start);
        return (end < 0 ? prompt.substring(start) : prompt.substring(start, end)).trim();
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Mock Agent 响应序列化失败", e);
        }
    }

}
