package com.example.demo.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ConfigurationQualityTest {

    private static final Path MAIN_JAVA =
            Path.of("src", "main", "java");
    private static final Path CONTROLLERS =
            MAIN_JAVA.resolve(Path.of(
                    "com", "example", "demo", "controller"));

    @Test
    void runtimeConfigurationShouldUseYamlOnly() {
        assertTrue(Files.isRegularFile(
                Path.of("src", "main", "resources", "application.yml")));
        assertTrue(Files.isRegularFile(Path.of(
                "src",
                "main",
                "resources",
                "application-loadtest.yml")));
        assertFalse(Files.exists(Path.of(
                "src",
                "main",
                "resources",
                "application.properties")));
        assertFalse(Files.exists(Path.of(
                "src",
                "main",
                "resources",
                "application-loadtest.properties")));
    }

    @Test
    void javaConfigurationInjectionShouldNotContainHiddenDefaults()
            throws IOException {
        String source = readJavaSources(MAIN_JAVA);
        assertFalse(
                source.matches(
                        "(?s).*@Value\\(\"\\$\\{[^}\\r\\n]+:"
                                + "[^}\\r\\n]*}\\\"\\).*"),
                "@Value 默认值必须统一声明在 application.yml");
        assertFalse(
                source.contains("@Autowired"),
                "使用构造器注入，禁止字段注入");
        assertFalse(
                source.matches("(?s).*sk-[A-Za-z0-9_-]{20,}.*"),
                "源码中不得出现 API Key");
    }

    @Test
    void controllersShouldRemainThinAndStreamingShouldBeReal()
            throws IOException {
        String controllerSource = readJavaSources(CONTROLLERS);
        String streamController = Files.readString(
                CONTROLLERS.resolve("StreamController.java"));
        assertFalse(controllerSource.contains("CompletableFuture"));
        assertFalse(streamController.contains("Thread.sleep"));
        assertFalse(streamController.contains("streamTokens"));
        assertFalse(streamController.contains("ChatService"));
        assertTrue(streamController.contains("StreamingChatUseCase"));
    }

    private String readJavaSources(Path root) throws IOException {
        StringBuilder source = new StringBuilder();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .forEach(path -> append(source, path));
        }
        return source.toString();
    }

    private void append(StringBuilder target, Path path) {
        try {
            target.append(Files.readString(path)).append('\n');
        } catch (IOException e) {
            throw new IllegalStateException(
                    "无法读取源码文件: " + path,
                    e);
        }
    }
}
