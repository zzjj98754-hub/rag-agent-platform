package com.example.demo.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.persistence.entity.ChatSessionEntity;
import com.example.demo.persistence.entity.DocumentEntity;
import com.example.demo.persistence.entity.UserEntity;
import com.example.demo.persistence.service.ChatHistoryPersistenceService;
import com.example.demo.persistence.service.DocumentPersistenceService;
import com.example.demo.persistence.service.UserPersistenceService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "app.ingestion.startup-enabled=false")
@Transactional
class BusinessPersistenceIntegrationTest {

    @Autowired
    private UserPersistenceService userPersistenceService;

    @Autowired
    private DocumentPersistenceService documentPersistenceService;

    @Autowired
    private ChatHistoryPersistenceService chatHistoryPersistenceService;

    @Test
    void shouldPersistUserDocumentSessionAndFullMessageHistory() {
        String suffix = UUID.randomUUID().toString().replace("-", "");

        UserEntity user = userPersistenceService.createUser(
                "user_" + suffix,
                "integration-secret",
                "user");
        assertThat(user.getId()).isNotNull();
        assertThat(user.getPassword()).isNotEqualTo("integration-secret");

        String filePath = "integration://" + suffix + "/document.txt";
        documentPersistenceService.markProcessing(
                "集成测试文档",
                filePath,
                user.getId());
        documentPersistenceService.markIndexed(filePath);
        DocumentEntity document = documentPersistenceService.findByFilePath(filePath);
        assertThat(document.getStatus()).isEqualTo("INDEXED");
        assertThat(document.getCreatorId()).isEqualTo(user.getId());

        String externalSessionId = "session_" + suffix;
        ChatSessionEntity session = chatHistoryPersistenceService.createSession(
                externalSessionId,
                user.getId(),
                "MySQL 持久化测试");
        assertThat(session.getId()).isNotNull();

        for (int i = 1; i <= 25; i++) {
            chatHistoryPersistenceService.appendMessage(
                    externalSessionId,
                    i % 2 == 0 ? "assistant" : "user",
                    "message-" + i);
        }

        assertThat(chatHistoryPersistenceService.getFullHistory(externalSessionId))
                .hasSize(25);
        assertThat(chatHistoryPersistenceService.getRecentMessages(externalSessionId, 10))
                .hasSize(10)
                .extracting(message -> message.getContent())
                .containsExactly(
                        "message-16", "message-17", "message-18", "message-19", "message-20",
                        "message-21", "message-22", "message-23", "message-24", "message-25");
    }
}
