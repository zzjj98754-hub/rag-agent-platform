package com.example.demo.mcp;

import com.example.demo.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;

/** Lifecycle owner for initialized MCP SDK clients and runtime tools. */
@Service
public class McpClientManager {
    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    private final McpClientFactory factory;
    private final ObjectMapper mapper;
    private final ToolRegistry toolRegistry;
    private final McpPersistenceService persistence;
    private final Map<String, McpServerDefinition> servers = new ConcurrentHashMap<>();
    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> discoveredTools = new ConcurrentHashMap<>();

    public McpClientManager(
            McpClientFactory factory,
            ObjectMapper mapper,
            ToolRegistry toolRegistry,
            McpPersistenceService persistence) {
        this.factory = factory;
        this.mapper = mapper;
        this.toolRegistry = toolRegistry;
        this.persistence = persistence;
    }

    public synchronized void register(McpServerDefinition definition) {
        validate(definition);
        disconnect(definition.name());
        persistence.save(definition);
        servers.put(definition.name(), definition);
        if (!definition.enabled()) {
            return;
        }
        try {
            clients.put(definition.name(), factory.create(definition));
            persistence.updateStatus(definition.name(), "CONNECTED", null);
        } catch (RuntimeException ex) {
            persistence.updateStatus(definition.name(), "FAILED", ex.getMessage());
            log.warn("MCP connection failed | server={} error={}",
                    definition.name(), ex.getMessage());
            throw ex;
        }
    }

    public synchronized void unregister(String name) {
        disconnect(name);
        persistence.delete(name);
    }

    private void disconnect(String name) {
        McpSyncClient client = clients.remove(name);
        if (client != null) {
            client.closeGracefully();
        }
        servers.remove(name);
        List<String> ownedTools = discoveredTools.keySet().stream()
                .filter(key -> key.startsWith(name + "."))
                .toList();
        ownedTools.forEach(toolRegistry::unregister);
        ownedTools.forEach(discoveredTools::remove);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadEnabledServers() {
        try {
            for (McpServerDefinition definition : persistence.findEnabled()) {
                try {
                    servers.put(definition.name(), definition);
                    clients.put(definition.name(), factory.create(definition));
                    discover(definition.name());
                    persistence.updateStatus(definition.name(), "CONNECTED", null);
                } catch (Exception ex) {
                    log.warn("MCP startup connection failed | server={} error={}",
                            definition.name(), ex.getMessage());
                    persistence.updateStatus(
                            definition.name(), "FAILED", ex.getMessage());
                }
            }
        } catch (DataAccessException ex) {
            log.warn("MCP persistence unavailable during startup: {}", ex.getMessage());
        }
    }

    public List<McpServerDefinition> servers() {
        return servers.values().stream()
                .map(McpServerDefinition::redacted)
                .toList();
    }

    public Map<String, Map<String, Object>> tools() {
        return Map.copyOf(discoveredTools);
    }

    public String callTool(String serverName, String toolName, Map<String, Object> arguments)
            throws IOException {
        McpSyncClient client = requireClient(serverName);
        try {
            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(
                            toolName, arguments == null ? Map.of() : arguments));
            if (Boolean.TRUE.equals(result.isError())) {
                throw new IOException("MCP tool reported an error: " + result.content());
            }
            return mapper.writeValueAsString(result);
        } catch (RuntimeException ex) {
            markUnavailable(serverName);
            throw new IOException("MCP tools/call failed: " + ex.getMessage(), ex);
        }
    }

    public synchronized void discover(String serverName) throws IOException {
        McpSyncClient client = requireClient(serverName);
        try {
            for (McpSchema.Tool tool : client.listTools().tools()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> schema = mapper.convertValue(
                        tool.inputSchema(), Map.class);
                McpToolAdapter adapter = new McpToolAdapter(
                        this,
                        serverName,
                        tool.name(),
                        tool.description(),
                        schema,
                        Set.of("MCP_TOOL"));
                toolRegistry.register(adapter);
                discoveredTools.put(adapter.name(), Map.of(
                        "name", adapter.name(),
                        "description", adapter.description(),
                        "parameters", adapter.parametersSchema(),
                        "available", true));
            }
        } catch (RuntimeException ex) {
            markUnavailable(serverName);
            throw new IOException("MCP tools/list failed: " + ex.getMessage(), ex);
        }
    }

    private void markUnavailable(String serverName) {
        discoveredTools.replaceAll((name, value) -> name.startsWith(serverName + ".")
                ? mergeAvailability(value, false)
                : value);
    }

    private Map<String, Object> mergeAvailability(
            Map<String, Object> value, boolean available) {
        java.util.LinkedHashMap<String, Object> copy = new java.util.LinkedHashMap<>(value);
        copy.put("available", available);
        return Map.copyOf(copy);
    }

    private McpSyncClient requireClient(String serverName) {
        McpServerDefinition server = servers.get(serverName);
        McpSyncClient client = clients.get(serverName);
        if (server == null || !server.enabled() || client == null || !client.isInitialized()) {
            throw new IllegalArgumentException("MCP Server unavailable: " + serverName);
        }
        return client;
    }

    private void validate(McpServerDefinition definition) {
        if (definition == null || definition.name() == null || definition.name().isBlank()) {
            throw new IllegalArgumentException("MCP Server name is required");
        }
        if (!definition.name().matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid MCP Server name");
        }
    }

    @PreDestroy
    public void close() {
        List.copyOf(clients.keySet()).forEach(this::disconnect);
    }
}
