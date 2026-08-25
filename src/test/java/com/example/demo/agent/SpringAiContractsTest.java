package com.example.demo.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.memory.SpringAiChatMemory;
import com.example.demo.service.ResilientEmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.ToolCallingManager;

class SpringAiContractsTest {
    @Test
    void platformBoundariesUseOfficialSpringAiContracts() {
        assertThat(EmbeddingModel.class)
                .isAssignableFrom(ResilientEmbeddingService.class);
        assertThat(ChatMemory.class)
                .isAssignableFrom(SpringAiChatMemory.class);
        assertThat(ToolCallingManager.class)
                .isAssignableFrom(GuardedToolCallingManager.class);
    }
}
