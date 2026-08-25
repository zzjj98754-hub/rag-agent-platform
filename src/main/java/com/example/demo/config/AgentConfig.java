package com.example.demo.config;

import com.example.demo.agent.tool.ToolExecutor;
import com.example.demo.agent.tool.ToolOutputSanitizer;
import com.example.demo.agent.AgentExecutor;
import com.example.demo.agent.AgentLlmClient;
import com.example.demo.agent.tool.ToolRegistry;
import com.example.demo.agent.tool.ToolScheduler;
import com.example.demo.security.AuthenticatedSessionService;
import com.example.demo.security.CurrentUserProvider;
import com.example.demo.service.ChatSessionService;
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

    @Bean
    public ToolExecutor toolExecutor(
            ExecutorService agentToolExecutor,
            @Value("${app.agent.step-timeout-ms}") long stepTimeoutMs,
            ToolOutputSanitizer outputSanitizer) {
        return new ToolExecutor(agentToolExecutor, stepTimeoutMs, outputSanitizer);
    }

    @Bean
    public AgentExecutor agentExecutor(
            AgentLlmClient llmClient,
            ToolRegistry toolRegistry,
            ToolScheduler toolScheduler,
            ChatSessionService chatSessionService,
            CurrentUserProvider currentUserProvider,
            AuthenticatedSessionService authenticatedSessionService,
            ToolOutputSanitizer outputSanitizer,
            @Value("${app.agent.max-steps}") int maxSteps) {
        return new AgentExecutor(
                llmClient, toolRegistry, toolScheduler, chatSessionService,
                currentUserProvider, authenticatedSessionService,
                outputSanitizer, maxSteps);
    }
}
