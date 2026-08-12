package com.example.demo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class InMemoryVectorStoreTest {

    private final InMemoryVectorStore store =
            new InMemoryVectorStore(
                    new ObjectMapper(),
                    mock(StringRedisTemplate.class));

    @Test
    void shouldRejectEmptyVectors() {
        store.add(
                "empty",
                "text",
                new float[0],
                0,
                "fallback",
                null,
                null);

        assertThat(store.size()).isZero();
    }

    @Test
    void shouldSkipVectorsWithIncompatibleDimensions() {
        store.add(
                "two-dimensional",
                "text",
                new float[] {1, 0},
                2,
                "model-a",
                null,
                null);

        assertThatCode(() -> store.search(
                new float[] {1, 0, 0},
                3)).doesNotThrowAnyException();
        assertThat(store.search(
                new float[] {1, 0, 0},
                3)).isEmpty();
    }
}
