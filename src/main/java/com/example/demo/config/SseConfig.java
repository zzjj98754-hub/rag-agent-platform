package com.example.demo.config;

import com.example.demo.service.SseReplayBuffer;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class SseConfig {

    @Bean
    public SseReplayBuffer sseReplayBuffer(
            @Value("${app.llm.streaming.replay.max-events}") int maxEvents,
            @Value("${app.llm.streaming.replay.ttl-ms}") long ttlMillis) {
        return new SseReplayBuffer(maxEvents, ttlMillis);
    }

    @Bean(name = "sseSendExecutor")
    public ThreadPoolTaskExecutor sseSendExecutor(
            @Value("${app.llm.streaming.send-executor.core-size}")
                    int coreSize,
            @Value("${app.llm.streaming.send-executor.max-size}")
                    int maxSize,
            @Value("${app.llm.streaming.send-executor.queue-capacity}")
                    int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int safeCore = Math.max(1, coreSize);
        executor.setCorePoolSize(safeCore);
        executor.setMaxPoolSize(Math.max(safeCore, maxSize));
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setThreadNamePrefix("sse-send-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setRejectedExecutionHandler(
                new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService sseHeartbeatScheduler(
            @Value("${app.llm.streaming.heartbeat-threads}")
                    int threadCount) {
        ScheduledThreadPoolExecutor scheduler =
                new ScheduledThreadPoolExecutor(
                        Math.max(1, threadCount),
                        runnable -> {
                            Thread thread = new Thread(
                                    runnable,
                                    "sse-heartbeat");
                            thread.setDaemon(true);
                            return thread;
                        });
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
