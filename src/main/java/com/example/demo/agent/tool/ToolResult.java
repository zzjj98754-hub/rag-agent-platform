package com.example.demo.agent.tool;

/**
 * Tool 执行结果。
 *
 * @param success 是否成功
 * @param content 返回内容（给 LLM 看的文本）
 * @param error   错误信息（success=false 时填充）
 * @param toolName 工具名称（用于审计）
 * @param elapsedMs 执行耗时
 */
public record ToolResult(
        boolean success,
        String content,
        String error,
        String toolName,
        long elapsedMs
) {
    public static ToolResult ok(String toolName, String content, long elapsedMs) {
        return new ToolResult(true, content, null, toolName, elapsedMs);
    }

    public static ToolResult fail(String toolName, String error, long elapsedMs) {
        return new ToolResult(false, null, error, toolName, elapsedMs);
    }
}
