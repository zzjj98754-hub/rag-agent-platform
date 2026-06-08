package com.example.demo.agent.tool;

import java.util.ArrayList;
import java.util.HashMap;
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

    private final Map<String, ToolDefinition> tools = new HashMap<>();

    /** Spring 自动注入所有 ToolDefinition Bean */
    public ToolRegistry(List<ToolDefinition> toolList) {
        for (ToolDefinition tool : toolList) {
            tools.put(tool.name(), tool);
            log.info("已注册工具: {} (权限: {})", tool.name(), tool.requiredPermissions());
        }
    }

    /** 按名称查找工具 */
    public ToolDefinition get(String name) {
        return tools.get(name);
    }

    /** 工具是否存在 */
    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    /** 获取所有工具名 */
    public Set<String> toolNames() {
        return tools.keySet();
    }

    /**
     * 返回 LLM 可用的工具列表（OpenAI Function Calling 格式）。
     */
    public List<Map<String, Object>> listToolsForLLM() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ToolDefinition tool : tools.values()) {
            Map<String, Object> item = new HashMap<>();
            item.put("name", tool.name());
            item.put("description", tool.description());
            item.put("parameters", tool.parametersSchema());
            result.add(item);
        }
        return result;
    }

}
