package com.example.demo.service;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * 外部 LLM HTTP 客户端 —— 封装超时处理与降级逻辑。
 *
 * 面试要点：
 * - ResourceAccessException 是 RestTemplate 对 I/O 异常的包装
 * - getCause() 可区分 ConnectException（建连失败）vs SocketTimeoutException（读取超时）
 * - 降级策略：超时时返回预设回复而非抛异常，保证核心链路可用
 * - 生产环境应接入熔断器（Resilience4j / Sentinel）做更精细的控制
 */
@Service
public class ExternalLlmClient {

    private static final Logger log = LoggerFactory.getLogger(ExternalLlmClient.class);

    @Autowired
    @Qualifier("llmRestTemplate")
    private RestTemplate restTemplate;

    @Value("${app.llm.url:http://localhost:9090/mock-llm}")
    private String llmUrl;

    /**
     * 调用外部 LLM API，超时时自动降级。
     *
     * @param prompt 构建好的完整 prompt
     * @param model  模型名称（可选，用于后端路由）
     * @return LLM 返回文本
     */
    public String callLlm(String prompt, String model) {
        Map<String, String> body = Map.of("prompt", prompt, "model", model);

        try {
            long start = System.currentTimeMillis();
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.postForObject(llmUrl, body, Map.class);
            long elapsed = System.currentTimeMillis() - start;

            if (resp != null && resp.get("content") != null) {
                String content = (String) resp.get("content");
                log.info("LLM 调用成功 | 耗时={}ms | 响应长度={}", elapsed, content.length());
                return content;
            }
            log.warn("LLM 响应格式异常，使用降级回复 | 耗时={}ms", elapsed);
            return fallbackResponse(prompt);

        } catch (ResourceAccessException e) {
            // 网络层异常：连接超时 / 读取超时 / DNS 解析失败等
            return handleTimeout(e, prompt);
        } catch (RestClientException e) {
            // HTTP 层异常：4xx / 5xx 等
            log.error("LLM HTTP 异常: {}", e.getMessage());
            return fallbackResponse(prompt);
        } catch (Exception e) {
            log.error("LLM 调用未知异常: {}", e.getMessage(), e);
            return fallbackResponse(prompt);
        }
    }

    /**
     * 超时异常的分类处理 —— 不同超时类型对应不同排查方向。
     */
    private String handleTimeout(ResourceAccessException e, String prompt) {
        Throwable cause = e.getCause();

        if (cause instanceof SocketTimeoutException) {
            // 读取超时：TCP 已建连，但服务端未在 readTimeout 内返回数据
            log.error("LLM 读取超时（服务端处理过慢或无响应）: {}", e.getMessage());
            return "【系统提示】LLM 服务响应超时，请稍后重试。以下是基于本地知识的简要回复：\n\n"
                    + fallbackResponse(prompt);

        } else if (cause instanceof ConnectException) {
            // 连接超时：TCP 握手失败，目标不可达
            log.error("LLM 连接超时（目标服务不可达）: {}", e.getMessage());
            return "【系统提示】LLM 服务暂时不可用，已切换至本地知识库模式。\n\n"
                    + fallbackResponse(prompt);

        } else {
            // DNS 解析失败、连接被拒绝等
            log.error("LLM 网络异常: {}", e.getMessage());
            return fallbackResponse(prompt);
        }
    }

    /**
     * 降级回复 —— 基于 prompt 关键词的本地规则引擎。
     * 当外部 LLM 不可用时，保证用户仍能得到有意义回复。
     */
    private String fallbackResponse(String prompt) {
        StringBuilder answer = new StringBuilder();
        answer.append("【本地降级回复】\n\n");

        if (prompt.contains("缓存穿透")) {
            answer.append("缓存穿透：查询 DB 中不存在的数据，每次绕过缓存直击 DB。\n");
            answer.append("解决：1) 布隆过滤器预判 2) 缓存空值标记。\n");
        } else if (prompt.contains("IoC") || prompt.contains("控制反转")) {
            answer.append("IoC 是 Spring 核心：将对象创建权交给容器，通过 DI 注入依赖。\n");
        } else if (prompt.contains("多线程")) {
            answer.append("Java 多线程核心：Thread/Runnable、synchronized/Lock、线程池七大参数。\n");
        } else if (prompt.contains("没有找到相关文档")) {
            answer.append("该问题暂未收录，建议补充相关文档后重试。\n");
        } else {
            answer.append("已收到您的问题。由于 LLM 服务暂时不可用，正在使用本地知识库为您解答。\n");
        }

        return answer.toString();
    }

}
