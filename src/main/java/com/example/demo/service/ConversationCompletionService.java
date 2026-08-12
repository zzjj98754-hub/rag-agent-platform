package com.example.demo.service;

import com.example.demo.rag.CitationValidator;
import org.springframework.stereotype.Service;

/**
 * 生成完成后的引用校验与会话持久化服务。
 */
@Service
public class ConversationCompletionService {

    private final CitationValidator citationValidator;
    private final ChatSessionService sessionService;

    public ConversationCompletionService(
            CitationValidator citationValidator,
            ChatSessionService sessionService) {
        this.citationValidator = citationValidator;
        this.sessionService = sessionService;
    }

    public void complete(
            PreparedRagPrompt preparedPrompt,
            String response) {
        citationValidator.validate(
                response,
                preparedPrompt.documents().size());
        if (preparedPrompt.sessionId() != null) {
            sessionService.appendMessage(
                    preparedPrompt.sessionId(),
                    "user",
                    preparedPrompt.query());
            sessionService.appendMessage(
                    preparedPrompt.sessionId(),
                    "assistant",
                    response);
        }
    }
}
