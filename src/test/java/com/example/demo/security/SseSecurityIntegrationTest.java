package com.example.demo.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.persistence.service.UserPersistenceService;
import com.example.demo.service.StreamingChatUseCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@SpringBootTest(properties = {
        "app.ingestion.startup-enabled=false",
        "app.security.jwt.secret=sse-integration-test-secret-with-more-than-thirty-two-bytes",
        "management.health.redis.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
class SseSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserPersistenceService userPersistenceService;

    @MockBean
    private StreamingChatUseCase streamingChatUseCase;

    @Test
    void authenticatedSseShouldCompleteAsyncDispatchWithoutLateForbidden()
            throws Exception {
        String username = "sse_"
                + UUID.randomUUID().toString().replace("-", "");
        userPersistenceService.createUser(
                username,
                "password-123",
                "USER");
        String token = login(username);
        AtomicReference<SseEmitter> emitterReference =
                new AtomicReference<>();
        when(streamingChatUseCase.streamChat(any(), any(), any()))
                .thenAnswer(invocation -> {
                    SseEmitter emitter = new SseEmitter(30_000L);
                    emitterReference.set(emitter);
                    return emitter;
                });

        MvcResult streamingResult = mockMvc.perform(
                        get("/chat/stream")
                                .param("query", "Redis")
                                .param("sessionId", "sse-security-test")
                                .header(
                                        "Authorization",
                                        "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        emitterReference.get().complete();

        mockMvc.perform(asyncDispatch(streamingResult))
                .andExpect(status().isOk());
    }

    private String login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "username", username,
                                "password", "password-123"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(
                result.getResponse().getContentAsByteArray());
        return body.path("accessToken").asText();
    }
}
