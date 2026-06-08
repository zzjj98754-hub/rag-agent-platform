package com.example.demo.controller;

import com.example.demo.service.ChatService;
import com.example.demo.service.ChatSessionService;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 流式输出 Controller —— 模拟大模型 token-by-token 推送。
 *
 * 面试要点：
 * - SSE (Server-Sent Events) 是 HTTP 长连接，服务端单向推送
 * - SseEmitter 是 Spring 对 SSE 的封装，线程安全
 * - 关键配置：超时时间、异常回调、完成回调
 * - 相比 WebSocket：SSE 更轻量，只需服务端→客户端推送时首选
 */
@RestController
public class StreamController {

    private static final Logger log = LoggerFactory.getLogger(StreamController.class);

    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatSessionService sessionService;

    /**
     * SSE 流式聊天端点。
     *
     * @param query     用户问题
     * @param sessionId 会话 ID（可选，不传则创建新会话）
     * @return SseEmitter 流式响应
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @RequestParam String query,
            @RequestParam(required = false) String sessionId) {

        // 超时 5 分钟
        SseEmitter emitter = new SseEmitter(300_000L);

        final String sid = (sessionId == null || sessionId.isBlank())
                ? sessionService.createSession()
                : sessionId;

        CompletableFuture.runAsync(() -> {
            try {
                // 1. 发送会话信息
                emit(emitter, "session", Map.of("sessionId", sid));

                // 2. RAG 检索
                emit(emitter, "context", Map.of(
                        "phase", "retrieving",
                        "message", "正在检索相关文档..."
                ));

                String fullResponse = chatService.askWithContext(query, sid);

                // 3. 流式输出 LLM 回复（模拟 token-by-token）
                emit(emitter, "context", Map.of(
                        "phase", "generating",
                        "message", "正在生成回答..."
                ));

                streamTokens(emitter, fullResponse);

                // 4. 完成信号
                emit(emitter, "done", Map.of(
                        "sessionId", sid,
                        "length", fullResponse.length()
                ));

                emitter.complete();
                log.info("SSE 流式完成 | session={} | 响应长度={}", sid, fullResponse.length());

            } catch (Exception e) {
                log.error("SSE 流式异常 | session={}: {}", sid, e.getMessage());
                try {
                    emit(emitter, "error", Map.of("message", e.getMessage()));
                } catch (IOException ignored) {}
                emitter.completeWithError(e);
            }
        });

        emitter.onTimeout(() -> log.warn("SSE 连接超时 | session={}", sid));
        emitter.onError(throwable -> log.error("SSE 连接异常 | session={}: {}", sid, throwable.getMessage()));

        return emitter;
    }

    /**
     * 模拟 token-by-token 流式推送。
     * 生产环境替换为 WebClient 对接 OpenAI/Anthropic 的 streaming API。
     */
    private void streamTokens(SseEmitter emitter, String text) throws IOException, InterruptedException {
        // 按字符分组输出，中文单字一组，英文/数字按词一组
        int i = 0;
        while (i < text.length()) {
            int end = i + 1;
            char c = text.charAt(i);
            if (c > 127) {
                // 中文字符，单字推送
                end = i + 1;
            } else {
                // ASCII 字符，尽量按词边界切分
                while (end < text.length() && text.charAt(end) <= 127
                        && text.charAt(end) != ' ' && text.charAt(end) != '\n') {
                    end++;
                }
            }
            String token = text.substring(i, end);
            emit(emitter, "token", Map.of("content", token));
            i = end;

            // 模拟生成延迟（10-50ms），让流式效果可见
            Thread.sleep(10 + (long) (Math.random() * 40));
        }
    }

    private void emit(SseEmitter emitter, String event, Object data) throws IOException {
        emitter.send(SseEmitter.event()
                .name(event)
                .data(data, MediaType.APPLICATION_JSON));
    }

}
