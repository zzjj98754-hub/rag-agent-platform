package com.example.demo.controller;

import com.example.demo.service.StreamingChatUseCase;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 流式聊天 HTTP 入口，仅负责参数校验与用例委派。
 */
@Validated
@RestController
public class StreamController {

    private final StreamingChatUseCase streamingChatUseCase;

    public StreamController(
            StreamingChatUseCase streamingChatUseCase) {
        this.streamingChatUseCase = streamingChatUseCase;
    }

    @GetMapping(
            value = "/chat/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @RequestParam
            @NotBlank(message = "query 不能为空")
            @Size(max = 8000, message = "query 长度不能超过 8000")
            String query,
            @RequestParam(required = false)
            @Size(max = 64, message = "sessionId 长度不能超过 64")
            String sessionId,
            @RequestHeader(
                    value = "Last-Event-ID",
                    required = false)
            String lastEventId) {
        return streamingChatUseCase.streamChat(
                query,
                sessionId,
                lastEventId);
    }
}
