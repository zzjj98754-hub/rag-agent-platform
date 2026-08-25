package com.example.demo.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.mcp.McpPersistenceService;
import com.example.demo.mcp.McpServerDefinition;
import com.example.demo.skill.SkillDefinition;
import com.example.demo.skill.SkillRegistry;
import com.example.demo.skill.SkillVersion;
import com.example.demo.workflow.WorkflowDefinition;
import com.example.demo.workflow.WorkflowNode;
import com.example.demo.workflow.WorkflowRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "app.ingestion.startup-enabled=false")
class AgentPlatformPersistenceIntegrationTest {
    @Autowired SkillRegistry skills;
    @Autowired WorkflowRegistry workflows;
    @Autowired McpPersistenceService mcp;
    @Autowired JdbcTemplate jdbc;

    private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private final String skillCode = "test-skill-" + suffix;
    private final String workflowCode = "test-workflow-" + suffix;
    private final String mcpName = "test-mcp-" + suffix;

    @Test
    void shouldPersistMcpSkillVersionsAndWorkflowDefinitions() {
        skills.register(new SkillDefinition(
                skillCode, "Test Skill", "integration", 0, true,
                List.of(), Map.of("type", "object", "required", List.of("topic")),
                "ADMIN"));
        SkillVersion published = skills.publish(new SkillVersion(
                skillCode, 0, "Summarize {{topic}}", List.of(),
                "initial", "test"));
        assertThat(published.version()).isEqualTo(1);
        assertThat(skills.resolveVersion(skillCode, null).promptTemplate())
                .isEqualTo("Summarize {{topic}}");

        WorkflowDefinition workflow = new WorkflowDefinition(
                workflowCode, 1, true, List.of(new WorkflowNode(
                        "generate", WorkflowNode.Type.LLM, List.of(),
                        Map.of("prompt", "hello"))));
        workflows.save(workflow, 1L);
        assertThat(workflows.get(workflowCode)).isEqualTo(workflow);

        mcp.save(new McpServerDefinition(
                mcpName, McpServerDefinition.Transport.STREAMABLE_HTTP,
                null, List.of(), "http://127.0.0.1:65535/mcp", null, false));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mcp_server WHERE name=?", Integer.class, mcpName);
        assertThat(count).isEqualTo(1);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE sv FROM skill_version sv JOIN skill s ON s.id=sv.skill_id WHERE s.code=?",
                skillCode);
        jdbc.update("DELETE FROM skill WHERE code=?", skillCode);
        jdbc.update("DELETE FROM workflow_definition WHERE code=?", workflowCode);
        jdbc.update("DELETE FROM mcp_server WHERE name=?", mcpName);
    }
}
