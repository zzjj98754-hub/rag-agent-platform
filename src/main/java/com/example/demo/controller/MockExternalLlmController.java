package com.example.demo.controller;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模拟外部 LLM API 端点 —— 用于演示 HTTP 超时处理。
 *
 * 面试要点：
 * - 通过 delay 参数模拟慢服务，验证客户端超时配置是否生效
 * - 返回标准 JSON 格式：{ "content": "...", "model": "...", "tokens": N }
 * - 真实场景替换为 OpenAI / Anthropic / 阿里通义等 API 调用
 */
@RestController
public class MockExternalLlmController {

    private static final Logger log = LoggerFactory.getLogger(MockExternalLlmController.class);

    /**
     * 模拟 LLM API。
     *
     * 请求体：{ "prompt": "...", "model": "...", "delay": 可选(ms) }
     * 响应体：{ "content": "...", "model": "...", "tokens": N }
     */
    @PostMapping("/mock-llm")
    public Map<String, Object> mockLlm(@RequestBody Map<String, Object> request) {
        String prompt = (String) request.getOrDefault("prompt", "");
        String model = (String) request.getOrDefault("model", "default");

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
        return 100 + ThreadLocalRandom.current().nextLong(101);
    }

    private String generateMockResponse(String prompt) {
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

}
