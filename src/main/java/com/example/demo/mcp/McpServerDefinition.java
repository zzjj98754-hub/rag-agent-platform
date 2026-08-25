package com.example.demo.mcp;

import java.util.List;

/** Persistable MCP connection definition. Secrets are supplied separately. */
public record McpServerDefinition(
        String name,
        Transport transport,
        String command,
        List<String> commandArgs,
        String url,
        String authHeader,
        boolean enabled) {

    public enum Transport { STDIO, STREAMABLE_HTTP, SSE, HTTP_SSE }

    public McpServerDefinition {
        commandArgs = commandArgs == null ? List.of() : List.copyOf(commandArgs);
        transport = transport == null ? Transport.HTTP_SSE : transport;
    }

    public McpServerDefinition redacted() {
        return new McpServerDefinition(
                name, transport, command, commandArgs, url, null, enabled);
    }
}
