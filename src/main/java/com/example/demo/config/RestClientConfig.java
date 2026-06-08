package com.example.demo.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP 客户端超时配置。
 *
 * 面试要点：
 * - 连接超时 (connectTimeout)：TCP 三次握手阶段，目标不可达时快速失败
 * - 读取超时 (readTimeout)：已建连后等待响应数据，防止服务端 hang 住客户端线程
 * - RestTemplate 默认无超时（无限等待），生产必须显式配置
 * - JDK 17+ 可用 JdkClientHttpRequestFactory（基于 java.net.http.HttpClient）
 */
@Configuration
public class RestClientConfig {

    @Value("${app.llm.connect-timeout:3}")
    private long connectTimeout;

    @Value("${app.llm.read-timeout:30}")
    private long readTimeout;

    @Bean
    public RestTemplate llmRestTemplate(RestTemplateBuilder builder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeout))
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(readTimeout));

        return new RestTemplate(factory);
    }

}
