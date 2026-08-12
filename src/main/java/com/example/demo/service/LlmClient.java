package com.example.demo.service;

/**
 * 同步 LLM 能力边界。
 *
 * <p>RAG、Query Rewrite 和 Agent 只依赖该最小接口，不感知 HTTP 客户端、
 * 模型供应商和降级实现。
 */
public interface LlmClient {

    String callLlm(String prompt, String model);
}
