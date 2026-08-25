package com.example.demo.controller;

import com.example.demo.mcp.McpClientManager;
import com.example.demo.mcp.McpServerDefinition;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/mcp/servers")
public class McpServerController {

    private final McpClientManager manager;

    public McpServerController(McpClientManager manager) { this.manager = manager; }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("servers", manager.servers(), "tools", manager.tools());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public McpServerDefinition register(@RequestBody McpServerDefinition definition)
            throws Exception {
        manager.register(definition);
        manager.discover(definition.name());
        return definition.redacted();
    }

    @GetMapping("/{name}/tools")
    public Map<String, Object> tools(@PathVariable String name) {
        return Map.of("server", name, "tools", manager.tools().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(name + "."))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> delete(@PathVariable String name) {
        manager.unregister(name);
        return Map.of("deleted", true, "server", name);
    }
}
