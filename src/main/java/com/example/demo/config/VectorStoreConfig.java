package com.example.demo.config;

import com.example.demo.service.InMemoryVectorStore;
import com.example.demo.service.ElasticsearchVectorStore;
import com.example.demo.service.RedisStackVectorStore;
import com.example.demo.service.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
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

    @Bean
    @Primary
    public VectorStore vectorStore(
            @Value("${app.vector-store.backend}") String backend,
            @Qualifier("inMemoryVectorStore") ObjectProvider<InMemoryVectorStore> inMemory,
            @Qualifier("redisStackVectorStore") ObjectProvider<RedisStackVectorStore> redisStack,
            ObjectProvider<ElasticsearchVectorStore> elasticsearch) {
        return switch (backend.trim().toLowerCase()) {
            case "in-memory", "memory" -> inMemory.getIfAvailable(() -> {
                throw new IllegalStateException("InMemory VectorStore 未启用");
            });
            case "redis", "redis-stack", "redis_stack" -> redisStack.getIfAvailable(() -> {
                throw new IllegalStateException("Redis Stack VectorStore 未启用");
            });
            case "elasticsearch", "es" -> elasticsearch.getIfAvailable(() -> {
                throw new IllegalStateException("Elasticsearch VectorStore 未启用");
            });
            default -> throw new IllegalArgumentException(
                    "不支持的 VectorStore 后端: " + backend);
        };
    }
}
