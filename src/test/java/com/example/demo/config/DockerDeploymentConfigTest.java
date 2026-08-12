package com.example.demo.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class DockerDeploymentConfigTest {

    @Test
    @SuppressWarnings("unchecked")
    void composeShouldDefineHealthyApplicationDependencies() throws Exception {
        String yamlText = Files.readString(Path.of("docker-compose.yml"));
        Map<String, Object> root = new Yaml().load(yamlText);
        Map<String, Object> services =
                (Map<String, Object>) root.get("services");

        assertThat(services).containsKeys("app", "mysql", "redis");
        Map<String, Object> app = (Map<String, Object>) services.get("app");
        Map<String, Object> dependsOn =
                (Map<String, Object>) app.get("depends_on");
        assertThat((Map<String, Object>) dependsOn.get("mysql"))
                .containsEntry("condition", "service_healthy");
        assertThat((Map<String, Object>) dependsOn.get("redis"))
                .containsEntry("condition", "service_healthy");

        Map<String, Object> environment =
                (Map<String, Object>) app.get("environment");
        assertThat(environment.get("SPRING_DATASOURCE_URL").toString())
                .contains("mysql:3306");
        assertThat(environment)
                .containsEntry("SPRING_DATA_REDIS_HOST", "redis")
                .containsEntry("RAG_DOCS_PATH", "/app/docs");
    }

    @Test
    void dockerfileShouldUseSeparateBuildAndRuntimeStages() throws Exception {
        String dockerfile = Files.readString(Path.of("Dockerfile"));

        assertThat(dockerfile)
                .contains("FROM maven:")
                .contains(" AS build")
                .contains("FROM eclipse-temurin:17-jre-alpine AS runtime")
                .contains("COPY --from=build")
                .contains("USER spring:spring")
                .contains("ENTRYPOINT [\"java\", \"-jar\", \"/app/app.jar\"]");
    }
}
