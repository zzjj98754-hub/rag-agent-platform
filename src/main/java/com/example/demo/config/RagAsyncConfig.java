package com.example.demo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class RagAsyncConfig {

    @Bean(name = "ragRetrievalExecutor")
    public ThreadPoolTaskExecutor ragRetrievalExecutor(
            @Value("${app.rag.executor.core-size}") int coreSize,
            @Value("${app.rag.executor.max-size}") int maxSize,
            @Value("${app.rag.executor.queue-capacity}") int queueCapacity) {
        return createExecutor(
                "rag-retrieval-",
                coreSize,
                maxSize,
                queueCapacity);
    }

    @Bean(name = "ragAsyncExecutor")
    public ThreadPoolTaskExecutor ragAsyncExecutor(
            @Value("${app.rag.async.core-size}") int coreSize,
            @Value("${app.rag.async.max-size}") int maxSize,
            @Value("${app.rag.async.queue-capacity}") int queueCapacity) {
        return createExecutor(
                "rag-async-",
                coreSize,
                maxSize,
                queueCapacity);
    }

    private ThreadPoolTaskExecutor createExecutor(
            String prefix,
            int coreSize,
            int maxSize,
            int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int safeCoreSize = Math.max(1, coreSize);
        executor.setCorePoolSize(safeCoreSize);
        executor.setMaxPoolSize(Math.max(safeCoreSize, maxSize));
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setThreadNamePrefix(prefix);
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
