package com.example.demo.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Builds initialized MCP SDK clients and enforces the STDIO command allowlist. */
@Component
public class McpClientFactory {
    private final McpJsonMapper jsonMapper;
    private final Duration timeout;
    private final Set<String> allowedCommands;

    public McpClientFactory(
            ObjectMapper objectMapper,
            @Value("${app.mcp.request-timeout-ms}") long timeoutMs,
            @Value("${app.mcp.allowed-commands}") String allowedCommands) {
        this.jsonMapper = new JacksonMcpJsonMapper(objectMapper);
        this.timeout = Duration.ofMillis(Math.max(1000, timeoutMs));
        this.allowedCommands = Arrays.stream(allowedCommands.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public McpSyncClient create(McpServerDefinition server) {
        McpClientTransport transport = switch (server.transport()) {
            case STDIO -> stdio(server);
            case SSE, HTTP_SSE -> sse(server);
            case STREAMABLE_HTTP -> streamableHttp(server);
        };
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(timeout)
                .initializationTimeout(timeout)
                .build();
        client.initialize();
        return client;
    }

    private McpClientTransport stdio(McpServerDefinition server) {
        if (server.command() == null || server.command().isBlank()) {
            throw new IllegalArgumentException("STDIO command is required");
        }
        String executable = Path.of(server.command()).getFileName().toString()
                .toLowerCase(Locale.ROOT);
        if (executable.endsWith(".exe")) {
            executable = executable.substring(0, executable.length() - 4);
        }
        if (!allowedCommands.contains(executable)) {
            throw new SecurityException("MCP STDIO command is not allowed: " + executable);
        }
        ServerParameters parameters = ServerParameters.builder(server.command())
                .args(server.commandArgs())
                .build();
        StdioClientTransport transport = new StdioClientTransport(parameters, jsonMapper);
        transport.setStdErrorHandler(line -> { });
        return transport;
    }

    private McpClientTransport streamableHttp(McpServerDefinition server) {
        requireUrl(server);
        var builder = HttpClientStreamableHttpTransport.builder(server.url())
                .jsonMapper(jsonMapper)
                .connectTimeout(timeout);
        if (hasSecret(server)) {
            builder.customizeRequest(request -> request.header(
                    "Authorization", server.authHeader()));
        }
        return builder.build();
    }

    private McpClientTransport sse(McpServerDefinition server) {
        requireUrl(server);
        var builder = HttpClientSseClientTransport.builder(server.url())
                .jsonMapper(jsonMapper)
                .connectTimeout(timeout);
        if (hasSecret(server)) {
            builder.customizeRequest(request -> request.header(
                    "Authorization", server.authHeader()));
        }
        return builder.build();
    }

    private void requireUrl(McpServerDefinition server) {
        if (server.url() == null || server.url().isBlank()) {
            throw new IllegalArgumentException("MCP HTTP URL is required");
        }
    }

    private boolean hasSecret(McpServerDefinition server) {
        return server.authHeader() != null && !server.authHeader().isBlank();
    }
}
