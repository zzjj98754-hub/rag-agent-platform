package com.example.demo.agent.tool;

import com.example.demo.service.EmbeddingService;
import com.example.demo.service.VectorStore;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 知识库检索工具 —— 搜索本地文档向量库。
 *
 * 权限：KNOWLEDGE_SEARCH —— 普通用户默认拥有
 */
@Component
public class SearchTool implements ToolDefinition {

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private VectorStore vectorStore;

    @Value("${app.rag.top-k:3}")
    private int topK;

    @Override
    public String name() {
        return "search_knowledge";
    }

    @Override
    public String description() {
        return "搜索本地知识库。当用户询问技术概念、框架原理等需要查文档的问题时使用。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", "搜索关键词或问题")
                ),
                "required", List.of("query")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        long start = System.currentTimeMillis();
        String query = (String) params.get("query");
        if (query == null || query.isBlank()) {
            return ToolResult.fail(name(), "query 参数不能为空", System.currentTimeMillis() - start);
        }

        float[] vec = embeddingService.embed(query);
        List<VectorStore.Result> results = vectorStore.search(vec, topK);

        if (results.isEmpty()) {
            return ToolResult.ok(name(), "未找到相关文档。", System.currentTimeMillis() - start);
        }

        String content = results.stream()
                .map(r -> "【" + r.id() + " 相似度:" + String.format("%.2f", r.score()) + "】\n" + r.text())
                .collect(Collectors.joining("\n\n---\n\n"));

        return ToolResult.ok(name(), content, System.currentTimeMillis() - start);
    }

    @Override
    public Set<String> requiredPermissions() {
        return Set.of("KNOWLEDGE_SEARCH");
    }

}
