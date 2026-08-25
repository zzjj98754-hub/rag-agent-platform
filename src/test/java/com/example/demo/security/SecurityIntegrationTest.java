package com.example.demo.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.persistence.service.UserPersistenceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

// Boot 3.3 测试默认禁用指标导出(management.defaults.metrics.export.enabled=false),
// 需显式 @AutoConfigureObservability 才能在测试上下文注册 /actuator/prometheus 端点
@SpringBootTest(properties = {
        "app.ingestion.startup-enabled=false",
        "app.security.jwt.secret=integration-test-jwt-secret-with-more-than-thirty-two-bytes",
        "management.health.redis.enabled=false"
})
@AutoConfigureMockMvc
@AutoConfigureObservability
@Transactional
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserPersistenceService userPersistenceService;

    private String adminUsername;
    private String userUsername;
    private String guestUsername;

    @BeforeEach
    void createUsers() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        adminUsername = "admin_" + suffix;
        userUsername = "user_" + suffix;
        guestUsername = "guest_" + suffix;
        userPersistenceService.createUser(adminUsername, "password-123", "ADMIN");
        userPersistenceService.createUser(userUsername, "password-123", "USER");
        userPersistenceService.createUser(guestUsername, "password-123", "GUEST");
    }

    @Test
    void protectedEndpointShouldRejectMissingToken() throws Exception {
        mockMvc.perform(get("/agent/tools"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.code").value(
                        "AUTHENTICATION_FAILED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void userShouldBeForbiddenFromAdminEndpointWhileAdminIsAllowed() throws Exception {
        String userToken = login(userUsername, "password-123");
        String adminToken = login(adminUsername, "password-123");

        mockMvc.perform(get("/admin/documents")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/documents")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void actuatorHealthShouldBePublicButMetricsShouldRequireAdmin() throws Exception {
        String adminToken = login(adminUsername, "password-123");

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
        // Prometheus 抓取端点放行:仅暴露指标序列,生产环境仅内网可达
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/metrics/rag.bm25.duration")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void guestCannotEscalateRoleThroughRequestBody() throws Exception {
        String guestToken = login(guestUsername, "password-123");

        mockMvc.perform(post("/agent/tool-call")
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "toolName", "calculator",
                                "params", Map.of("expression", "2+3"),
                                "role", "ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.denied").value(true))
                .andExpect(jsonPath("$.denyReason").value(
                        org.hamcrest.Matchers.containsString("无权限")));
    }

    @Test
    void authenticatedUserCanCallPermittedTool() throws Exception {
        String userToken = login(userUsername, "password-123");

        mockMvc.perform(post("/agent/tool-call")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "toolName", "calculator",
                                "params", Map.of("expression", "2+3")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.content").value("2+3 = 5.0"));
    }

    @Test
    void loginShouldRejectWrongPassword() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "username", userUsername,
                                "password", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void invalidChatRequestShouldUseUnifiedErrorEnvelope()
            throws Exception {
        String userToken = login(userUsername, "password-123");

        mockMvc.perform(post("/chat")
                        .header(
                                "Authorization",
                                "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "query", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(
                        "VALIDATION_FAILED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.details.query").isNotEmpty());
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "username", username,
                                "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn();
        JsonNode body = objectMapper.readTree(
                result.getResponse().getContentAsByteArray());
        return body.path("accessToken").asText();
    }
}
