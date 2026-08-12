package com.example.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ChatRequest {

    @NotBlank(message = "query 不能为空")
    @Size(max = 8000, message = "query 不能超过 8000 个字符")
    private String query;

    @Size(max = 64, message = "sessionId 不能超过 64 个字符")
    private String sessionId;

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

}
