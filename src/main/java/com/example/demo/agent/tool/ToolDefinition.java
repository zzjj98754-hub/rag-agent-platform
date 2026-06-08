package com.example.demo.agent.tool;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tool 策略接口 —— 每个工具实现此接口即注册到 ToolRegistry。
 *
 * 面试要点：
 * - 策略模式：每个 Tool 是独立策略，Registry 按 name 分发
 * - parametersSchema 用 JSON Schema 格式，供 LLM Function Calling 使用
 * - requiredPermissions 声明工具需要的权限，由 ToolPermissionEvaluator 校验
 */
public interface ToolDefinition {

    /** 工具唯一名称（LLM 通过此名称调用） */
    String name();

    /** 工具描述（给 LLM 看的，用于判断何时调用） */
    String description();

    /**
     * 参数 JSON Schema。
     * 格式示例：{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}
     */
    Map<String, Object> parametersSchema();

    /** 执行工具 */
    ToolResult execute(Map<String, Object> params);

    /** 该工具需要的权限标识，空集合表示无需权限 */
    default Set<String> requiredPermissions() {
        return Set.of();
    }

    /** 执行超时（毫秒），默认 30s */
    default long timeoutMs() {
        return 30_000;
    }

}
