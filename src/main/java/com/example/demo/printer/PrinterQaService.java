package com.example.demo.printer;

import com.example.demo.rag.HybridRetriever;
import com.example.demo.rag.SearchResult;
import com.example.demo.service.LlmClient;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 严格限制到所选型号资料范围的售后问答用例。 */
@Service
public class PrinterQaService {

    private final PrinterCatalogService catalog;
    private final HybridRetriever retriever;
    private final LlmClient llmClient;
    private final String model;
    private final int topK;

    public PrinterQaService(
            PrinterCatalogService catalog,
            HybridRetriever retriever,
            LlmClient llmClient,
            @Value("${app.llm.model}") String model,
            @Value("${app.rag.top-k}") int topK) {
        this.catalog = catalog;
        this.retriever = retriever;
        this.llmClient = llmClient;
        this.model = model;
        this.topK = topK;
    }

    public QaResponse ask(QaRequest request) {
        if (request == null || request.productId() == null) throw new IllegalArgumentException("必须先选择打印机型号");
        if (request.question() == null || request.question().isBlank()) throw new IllegalArgumentException("问题不能为空");
        if (request.question().trim().length() > 2000) throw new IllegalArgumentException("问题不能超过 2000 个字符");
        PrinterCatalogService.ProductScope scope = catalog.requireScope(request.productId());
        Set<String> allowedSources = scope.sourceByTitle().keySet();
        List<SearchResult> results = retriever.retrieve(request.question().trim(), Math.max(1, topK), allowedSources);
        List<Citation> citations = results.stream().map(result -> toCitation(result, scope.sourceByTitle())).toList();
        if (citations.isEmpty() || !hasLexicalEvidence(request.question().trim(), citations)) {
            return new QaResponse(scope.product(), "资料不足：所选型号的已索引资料中没有找到足以回答该问题的依据。请换一种故障描述，或提交售后申请让人工处理。", true, List.of());
        }
        String prompt = buildPrompt(scope.product(), citations, request.question().trim());
        String answer = llmClient.callLlm(prompt, model);
        return new QaResponse(scope.product(), answer, false, citations);
    }

    private Citation toCitation(SearchResult result, Map<String, PrinterCatalogService.SourceDocument> sources) {
        String title = sourceOf(result.id());
        PrinterCatalogService.SourceDocument source = sources.get(title);
        return new Citation(source == null ? null : source.id(), title, result.id(), result.score(), result.effectiveText());
    }

    private String buildPrompt(PrinterCatalogService.ProductView product, List<Citation> citations, String question) {
        StringBuilder prompt = new StringBuilder("PRINTER_AFTER_SALES_PROTOCOL_V1\n");
        prompt.append("你是打印机售后助手，只能依据所选型号的资料回答。型号：")
                .append(product.modelName()).append("。\n");
        prompt.append("禁止混用其他型号资料；禁止编造维修方法、保修承诺或资料中没有的事实。\n");
        prompt.append("请用中文输出：1) 结论；2) 按顺序列出排查步骤；3) 若资料不足明确说资料不足。每个关键事实引用 [编号]。\n");
        prompt.append("--- 参考文档 ---\n");
        for (int i = 0; i < citations.size(); i++) {
            Citation citation = citations.get(i);
            prompt.append('[').append(i + 1).append("] (来源: ").append(citation.title()).append(") ")
                    .append(citation.snippet()).append('\n');
        }
        prompt.append("--- 文档结束 ---\n用户问题：").append(question);
        return prompt.toString();
    }

    private String sourceOf(String chunkId) {
        if (chunkId == null) return "";
        int separator = chunkId.indexOf(':');
        return separator > 0 ? chunkId.substring(0, separator) : chunkId;
    }

    /** 在语义召回后增加轻量依据门控，避免本地降级向量对任意问题都返回泛化答案。 */
    private boolean hasLexicalEvidence(String question, List<Citation> citations) {
        String normalized = question.toLowerCase();
        int matchedTerms = 0;
        for (int i = 0; i + 1 < normalized.length(); i++) {
            char first = normalized.charAt(i);
            char second = normalized.charAt(i + 1);
            if (Character.isWhitespace(first) || Character.isWhitespace(second)
                    || "，。！？；：,.!?;:".indexOf(first) >= 0
                    || "，。！？；：,.!?;:".indexOf(second) >= 0) continue;
            if (Character.isLetterOrDigit(first) && Character.isLetterOrDigit(second)) {
                String term = normalized.substring(i, i + 2);
                if (citations.stream().anyMatch(c -> c.snippet().toLowerCase().contains(term))) matchedTerms++;
            }
        }
        return matchedTerms >= 2;
    }

    public record QaRequest(Long productId, String question) {}
    public record QaResponse(PrinterCatalogService.ProductView product, String answer,
                             boolean insufficientEvidence, List<Citation> citations) {}
    public record Citation(Long documentId, String title, String chunkId, double score, String snippet) {}
}
