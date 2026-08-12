package com.example.demo.service;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class SseEmitterFactory {

    public SseEmitter create(long timeoutMillis) {
        return new SseEmitter(timeoutMillis);
    }
}
