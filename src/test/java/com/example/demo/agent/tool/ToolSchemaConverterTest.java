package com.example.demo.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolSchemaConverterTest {

    private final ToolSchemaConverter converter = new ToolSchemaConverter();

    @Test
    void shouldConvertDefinitionToOpenAiFunctionSchema() {
        ToolDefinition tool = testTool();

        Map<String, Object> function = converter.toFunctionSchema(tool);
        Map<String, Object> openAiTool = converter.toOpenAiTool(tool);

        assertThat(function)
                .containsEntry("name", "searchKnowledge")
                .containsEntry("description", "搜索知识库")
                .containsKey("parameters");
        assertThat(openAiTool).containsEntry("type", "function");
        assertThat(openAiTool.get("function")).isEqualTo(function);
    }

    @Test
    void registryShouldExposeFunctionAndWrappedToolFormats() {
        ToolRegistry registry =
                new ToolRegistry(List.of(testTool()), converter);

        assertThat(registry.listToolsForLLM())
                .singleElement()
                .extracting(schema -> schema.get("name"))
                .isEqualTo("searchKnowledge");
        assertThat(registry.listOpenAiTools())
                .singleElement()
                .extracting(schema -> schema.get("type"))
                .isEqualTo("function");
    }

    private ToolDefinition testTool() {
        return new ToolDefinition() {
            @Override
            public String name() {
                return "searchKnowledge";
            }

            @Override
            public String description() {
                return "搜索知识库";
            }

            @Override
            public Map<String, Object> parametersSchema() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string")),
                        "required", List.of("query"));
            }

            @Override
            public ToolResult execute(Map<String, Object> params) {
                return ToolResult.ok(name(), String.valueOf(params.get("query")), 1);
            }

            @Override
            public Set<String> requiredPermissions() {
                return Set.of();
            }
        };
    }
}
