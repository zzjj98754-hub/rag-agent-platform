package com.example.demo.controller;

import com.example.demo.dto.ChatRequest;
import com.example.demo.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/chat")
    public String chat(@Valid @RequestBody ChatRequest request) {
        return chatService.ask(request.getQuery(), request.getSessionId());
    }

}
