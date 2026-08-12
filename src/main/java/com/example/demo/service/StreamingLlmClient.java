package com.example.demo.service;

import reactor.core.publisher.Flux;

/**
 * 流式大模型最小能力接口，隔离业务编排与具体供应商协议。
 */
public interface StreamingLlmClient {

    Flux<String> streamChat(String prompt);
}
