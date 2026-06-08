package com.example.demo.config;

import com.example.demo.service.InMemoryVectorStore;
import com.example.demo.service.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * VectorStore 后端选择配置。
 *
 * 根据 app.vector-store.backend 决定注入哪个实现。
 * 当前只有 in-memory，未来扩展 redis-stack / pgvector 时
 * 只需在此处增加 case 分支 + 对应的 @ConditionalOnProperty 实现类。
 */
@Configuration
public class VectorStoreConfig {

    @Value("${app.vector-store.backend:in-memory}")
    private String backend;

    @Bean
    @Primary
    public VectorStore vectorStore(
            @Qualifier("inMemoryVectorStore") InMemoryVectorStore inMemory) {
        // 未来扩展：switch(backend) { case "redis-stack" -> redisStack; default -> inMemory; }
        return inMemory;
    }
}
