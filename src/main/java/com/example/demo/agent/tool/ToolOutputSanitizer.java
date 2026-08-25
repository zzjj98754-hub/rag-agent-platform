package com.example.demo.agent.tool;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounds untrusted tool output before it is fed back into an LLM. */
@Component
public class ToolOutputSanitizer {
    private final int maxChars;
    public ToolOutputSanitizer(@Value("${app.mcp.max-output-chars}") int maxChars) {
        this.maxChars = Math.max(256, maxChars);
    }
    public String sanitize(String toolName, String content) {
        String value = content == null ? "" : content;
        if (value.length() > maxChars) value = value.substring(0, maxChars) + "...[truncated]";
        return value;
    }
    public String forModel(String toolName, String content) {
        return "[UNTRUSTED_TOOL_OUTPUT name=" + toolName + "]\n"
                + sanitize(toolName, content);
    }
    public Map<String, Object> validate(Map<String, Object> value) {
        return value == null ? Map.of() : Map.copyOf(value);
    }
}
