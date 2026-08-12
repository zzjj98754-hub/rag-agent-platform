package com.example.demo.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {

    @Bean(name = "agentToolExecutor", destroyMethod = "shutdown")
    public ExecutorService agentToolExecutor(
            @Value("${app.agent.tool-threads}") int threadCount,
            @Value("${app.agent.tool-queue-capacity}") int queueCapacity) {
        int safeThreadCount = Math.max(1, threadCount);
        int safeQueueCapacity = Math.max(1, queueCapacity);
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(
                safeThreadCount,
                safeThreadCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(safeQueueCapacity),
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "agent-tool-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }
}
