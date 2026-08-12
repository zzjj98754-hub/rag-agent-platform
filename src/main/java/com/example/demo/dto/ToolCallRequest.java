package com.example.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

public class ToolCallRequest {

    @NotBlank(message = "toolName 不能为空")
    @Size(max = 64, message = "toolName 不能超过 64 个字符")
    private String toolName;
    private Map<String, Object> params;

    @Size(max = 64, message = "sessionId 不能超过 64 个字符")
    private String sessionId;

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

}
