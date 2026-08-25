package com.example.demo.config;

import com.example.demo.service.ResilientEmbeddingService;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPooled;

@Configuration
@ConditionalOnProperty(
        name = "app.vector-store.backend", havingValue = "redis-stack")
public class SpringAiVectorStoreConfig {
    @Bean(destroyMethod = "close")
    public JedisPooled vectorStoreJedis(
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port}") int port,
            @Value("${spring.data.redis.password}") String password) {
        return password == null || password.isBlank()
                ? new JedisPooled(host, port)
                : new JedisPooled(host, port, null, password);
    }

    @Bean
    public org.springframework.ai.vectorstore.VectorStore springAiRedisVectorStore(
            JedisPooled jedis,
            ResilientEmbeddingService embeddingModel,
            @Value("${app.vector-store.index-name}") String indexName,
            @Value("${app.vector-store.prefix}") String prefix) {
        return RedisVectorStore.builder(jedis, embeddingModel)
                .indexName(indexName)
                .prefix(prefix)
                .vectorAlgorithm(RedisVectorStore.Algorithm.HNSW)
                .metadataFields(
                        RedisVectorStore.MetadataField.tag("embeddingSource"),
                        RedisVectorStore.MetadataField.tag("modelName"),
                        RedisVectorStore.MetadataField.numeric("dimension"),
                        RedisVectorStore.MetadataField.tag("parentId"),
                        RedisVectorStore.MetadataField.text("parentText"))
                .initializeSchema(true)
                .build();
    }
}
