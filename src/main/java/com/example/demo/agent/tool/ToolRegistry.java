package com.example.demo.agent.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tool 注册中心 —— 存储所有已注册工具，按名称分发执行。
 *
 * 面试要点：
 * - 策略模式的 Registry：ToolDefinition 是策略接口，register 注入具体策略
 * - listToolsForLLM() 返回 LLM Function Calling 所需的工具列表格式
 * - 引入新工具只需 implements ToolDefinition + @Component，零侵入
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();
    private final ToolSchemaConverter schemaConverter;

    /** Spring 自动注入所有 ToolDefinition Bean */
    public ToolRegistry(
            List<ToolDefinition> toolList,
            ToolSchemaConverter schemaConverter) {
        this.schemaConverter = schemaConverter;
        for (ToolDefinition tool : toolList) {
            schemaConverter.toFunctionSchema(tool);
            if (tools.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalStateException("存在重复工具名称: " + tool.name());
            }
            log.info("已注册工具: {} (权限: {})", tool.name(), tool.requiredPermissions());
        }
    }

    /** 按名称查找工具 */
    public synchronized ToolDefinition get(String name) {
        return tools.get(name);
    }

    /** 工具是否存在 */
    public synchronized boolean contains(String name) {
        return tools.containsKey(name);
    }

    /** 获取所有工具名 */
    public synchronized Set<String> toolNames() {
        return Set.copyOf(tools.keySet());
    }

    /**
     * 返回 OpenAI function 对象列表。
     */
    public synchronized List<Map<String, Object>> listToolsForLLM() {
        return tools.values().stream()
                .map(schemaConverter::toFunctionSchema)
                .toList();
    }

    /**
     * 返回 OpenAI Chat Completions API 可直接使用的 tools 数组。
     */
    public synchronized List<Map<String, Object>> listOpenAiTools() {
        return tools.values().stream()
                .map(schemaConverter::toOpenAiTool)
                .toList();
    }

    /** Register a runtime tool discovered from an MCP server or Skill. */
    public synchronized void register(ToolDefinition tool) {
        if (tool == null || tool.name() == null || tool.name().isBlank()) {
            throw new IllegalArgumentException("工具名称不能为空");
        }
        schemaConverter.toFunctionSchema(tool);
        ToolDefinition existing = tools.putIfAbsent(tool.name(), tool);
        if (existing != null && existing != tool) {
            throw new IllegalStateException("存在重复工具名称: " + tool.name());
        }
        log.info("已注册运行时工具: {} (权限: {})", tool.name(), tool.requiredPermissions());
    }

    public synchronized void unregister(String name) {
        if (name != null) {
            tools.remove(name);
        }
    }

}
