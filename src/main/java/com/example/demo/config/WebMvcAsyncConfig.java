package com.example.demo.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 为 SseEmitter/Servlet Async 分派提供有界线程池，避免默认
 * SimpleAsyncTaskExecutor 在高并发流式请求下无限创建线程。
 */
@Configuration
public class WebMvcAsyncConfig implements WebMvcConfigurer {

    private final ThreadPoolTaskExecutor asyncExecutor;
    private final long defaultTimeoutMillis;

    public WebMvcAsyncConfig(
            @Qualifier("ragAsyncExecutor")
            ThreadPoolTaskExecutor asyncExecutor,
            @Value("${app.web.async.default-timeout-ms}")
            long defaultTimeoutMillis) {
        this.asyncExecutor = asyncExecutor;
        this.defaultTimeoutMillis = defaultTimeoutMillis;
    }

    @Override
    public void configureAsyncSupport(
            AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(asyncExecutor);
        configurer.setDefaultTimeout(defaultTimeoutMillis);
    }
}
