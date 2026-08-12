package com.example.demo.agent.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 将项目内部 ToolDefinition 转换为 OpenAI Function Calling Schema。
 */
@Component
public class ToolSchemaConverter {

    private static final Pattern VALID_FUNCTION_NAME =
            Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    /**
     * OpenAI function 对象：
     * {"name":"...","description":"...","parameters":{...}}
     */
    public Map<String, Object> toFunctionSchema(ToolDefinition tool) {
        validate(tool);
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", tool.name());
        function.put("description", tool.description());
        function.put("parameters", tool.parametersSchema());
        return Map.copyOf(function);
    }

    /**
     * Chat Completions API 的 tools 数组元素：
     * {"type":"function","function":{...}}
     */
    public Map<String, Object> toOpenAiTool(ToolDefinition tool) {
        return wrapFunctionSchema(toFunctionSchema(tool));
    }

    public List<Map<String, Object>> wrapFunctionSchemas(
            List<Map<String, Object>> functionSchemas) {
        return functionSchemas.stream()
                .map(this::wrapFunctionSchema)
                .toList();
    }

    public Map<String, Object> wrapFunctionSchema(Map<String, Object> functionSchema) {
        return Map.of(
                "type", "function",
                "function", functionSchema);
    }

    private void validate(ToolDefinition tool) {
        if (tool == null) {
            throw new IllegalArgumentException("tool 不能为空");
        }
        if (tool.name() == null
                || !VALID_FUNCTION_NAME.matcher(tool.name()).matches()) {
            throw new IllegalArgumentException(
                    "工具名称必须为 1-64 位字母、数字、下划线或连字符: " + tool.name());
        }
        if (tool.description() == null || tool.description().isBlank()) {
            throw new IllegalArgumentException("工具描述不能为空: " + tool.name());
        }
        if (tool.parametersSchema() == null) {
            throw new IllegalArgumentException("工具参数 Schema 不能为空: " + tool.name());
        }
    }
}
