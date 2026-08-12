package com.example.demo.agent;

import java.util.List;
import java.util.Map;

/**
 * Agent 决策模型抽象，可替换为任意支持 OpenAI Function Calling 的实现。
 */
public interface AgentLlmClient {

    AgentAction decide(
            String question,
            AgentContext context,
            List<Map<String, Object>> availableTools);
}
