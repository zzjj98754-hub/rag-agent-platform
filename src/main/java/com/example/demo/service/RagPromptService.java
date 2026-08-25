package com.example.demo.service;

import com.example.demo.rag.CitationFormatter;
import com.example.demo.rag.HybridRetriever;
import com.example.demo.rag.QueryRewriter;
import com.example.demo.rag.RelevanceGate;
import com.example.demo.rag.SearchResult;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.example.demo.memory.ConversationSummaryService;

/**
 * RAG 检索与 Prompt 构建服务，不负责调用模型或持久化回答。
 */
@Service
public class RagPromptService {

    private final ChatSessionService sessionService;
    private final QueryRewriter queryRewriter;
    private final HybridRetriever hybridRetriever;
    private final RelevanceGate relevanceGate;
    private final CitationFormatter citationFormatter;
    private final ConversationSummaryService summaries;
    private final int topK;

    public RagPromptService(
            ChatSessionService sessionService,
            QueryRewriter queryRewriter,
            HybridRetriever hybridRetriever,
            RelevanceGate relevanceGate,
            CitationFormatter citationFormatter,
            ConversationSummaryService summaries,
            @Value("${app.rag.top-k}") int topK) {
        this.sessionService = sessionService;
        this.queryRewriter = queryRewriter;
        this.hybridRetriever = hybridRetriever;
        this.relevanceGate = relevanceGate;
        this.citationFormatter = citationFormatter;
        this.summaries = summaries;
        this.topK = topK;
    }

    public PreparedRagPrompt prepare(String query, String sessionId) {
        String history = sessionId == null
                ? ""
                : sessionService.formatHistory(sessionId);
        if (sessionId != null) {
            var summary = summaries.maybeCompress(sessionId);
            if (summary != null) {
                history = summary.text() + '\n' + history;
            }
        }
        String rewrittenQuery = queryRewriter.rewrite(query, history);
        List<SearchResult> rawDocuments =
                hybridRetriever.retrieve(rewrittenQuery, topK);
        RelevanceGate.GateDecision gate =
                relevanceGate.evaluate(rawDocuments);
        List<SearchResult> documents = gate.effectiveDocs();
        String noDocumentsReason =
                gate.passed() ? null : gate.reason();
        return new PreparedRagPrompt(
                query,
                sessionId,
                buildPrompt(
                        query,
                        documents,
                        history,
                        noDocumentsReason),
                documents);
    }

    private String buildPrompt(
            String query,
            List<SearchResult> documents,
            String history,
            String noDocumentsReason) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个知识助手。请严格基于以下信息回答用户问题。\n\n");

        if (history != null && !history.isEmpty()) {
            prompt.append(history);
        }

        if (noDocumentsReason != null) {
            prompt.append("=== 重要提示 ===\n");
            prompt.append("参考文档中未找到与用户问题相关的信息（")
                    .append(noDocumentsReason)
                    .append("）。\n");
            prompt.append("请如实告知用户这一情况，建议用户补充相关知识文档。\n");
            prompt.append("禁止编造答案。禁止使用你训练数据中的知识。\n");
            prompt.append("只说你确定能从参考文档中找到的信息。\n\n");
            prompt.append("用户问题：「").append(query).append("」\n");
            prompt.append("请用中文回答：");
            return prompt.toString();
        }

        if (documents.isEmpty()) {
            prompt.append("用户问：「")
                    .append(query)
                    .append("」，但没有找到相关文档。请根据历史对话简要回答。");
            return prompt.toString();
        }

        prompt.append(citationFormatter.formatReferenceSection(documents));
        prompt.append("\n");
        prompt.append(citationFormatter.getCitationInstruction(
                documents.size()));
        prompt.append("\n\n");
        prompt.append("用户问题：「").append(query).append("」\n");
        prompt.append("请用中文回答（必须引用来源编号）：");
        return prompt.toString();
    }
}
