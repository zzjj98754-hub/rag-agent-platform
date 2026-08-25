package com.example.demo.mcp;

import com.example.demo.agent.tool.ToolDefinition;
import com.example.demo.agent.tool.ToolResult;
import java.util.Map;
import java.util.Set;

/** Bridges an MCP tools/call endpoint into the platform ToolDefinition contract. */
public final class McpToolAdapter implements ToolDefinition {

    private final McpClientManager client;
    private final String server;
    private final String tool;
    private final String description;
    private final Map<String, Object> schema;
    private final Set<String> permissions;

    public McpToolAdapter(McpClientManager client, String server, String tool,
            String description, Map<String, Object> schema, Set<String> permissions) {
        this.client = client;
        this.server = server;
        this.tool = tool;
        this.description = description == null ? "MCP tool" : description;
        this.schema = schema == null ? Map.of("type", "object") : Map.copyOf(schema);
        this.permissions = permissions == null ? Set.of("MCP_TOOL") : Set.copyOf(permissions);
    }

    @Override public String name() { return server + "." + tool; }
    @Override public String description() { return description; }
    @Override public Map<String, Object> parametersSchema() { return schema; }
    @Override public Set<String> requiredPermissions() { return permissions; }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        long started = System.currentTimeMillis();
        try {
            String result = client.callTool(server, tool, params == null ? Map.of() : params);
            return ToolResult.ok(name(), result, System.currentTimeMillis() - started);
        } catch (Exception ex) {
            return ToolResult.fail(name(), ex.getMessage(), System.currentTimeMillis() - started);
        }
    }
}
