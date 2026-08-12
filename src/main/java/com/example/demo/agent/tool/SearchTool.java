package com.example.demo.agent.tool;

import com.example.demo.rag.HybridRetriever;
import com.example.demo.rag.SearchResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 知识库检索工具 —— 复用 BM25 + Vector + RRF + Reranker 完整混合检索链路。
 *
 * 权限：KNOWLEDGE_SEARCH —— 普通用户默认拥有
 */
@Component
public class SearchTool implements ToolDefinition {

    private final HybridRetriever hybridRetriever;
    private final int topK;

    public SearchTool(
            HybridRetriever hybridRetriever,
            @Value("${app.rag.top-k}") int topK) {
        this.hybridRetriever = hybridRetriever;
        this.topK = topK;
    }

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

        List<SearchResult> results = hybridRetriever.retrieve(query, topK);

        if (results.isEmpty()) {
            return ToolResult.ok(name(), "未找到相关文档。", System.currentTimeMillis() - start);
        }

        String content = results.stream()
                .map(result -> "【"
                        + result.id()
                        + " "
                        + result.scoreType()
                        + ":"
                        + String.format("%.3f", result.score())
                        + "】\n"
                        + result.effectiveText())
                .collect(Collectors.joining("\n\n---\n\n"));

        return ToolResult.ok(name(), content, System.currentTimeMillis() - start);
    }

    @Override
    public Set<String> requiredPermissions() {
        return Set.of("KNOWLEDGE_SEARCH");
    }

}
