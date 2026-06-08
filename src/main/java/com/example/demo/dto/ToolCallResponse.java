package com.example.demo.dto;

import java.util.ArrayList;
import java.util.List;

public class ToolCallResponse {

    private String toolName;
    private boolean success;
    private String content;
    private String error;
    private boolean denied;
    private String denyReason;
    private long elapsedMs;
    private int stepCount;
    private List<String> toolHistory;

    public static ToolCallResponse ok(String toolName, String content, long elapsedMs, List<String> history) {
        ToolCallResponse r = new ToolCallResponse();
        r.toolName = toolName;
        r.success = true;
        r.content = content;
        r.elapsedMs = elapsedMs;
        r.stepCount = history.size();
        r.toolHistory = new ArrayList<>(history);
        return r;
    }

    public static ToolCallResponse denied(String toolName, String reason, List<String> history) {
        ToolCallResponse r = new ToolCallResponse();
        r.toolName = toolName;
        r.success = false;
        r.denied = true;
        r.denyReason = reason;
        r.stepCount = history.size();
        r.toolHistory = new ArrayList<>(history);
        return r;
    }

    public static ToolCallResponse error(String toolName, String error, List<String> history) {
        ToolCallResponse r = new ToolCallResponse();
        r.toolName = toolName;
        r.success = false;
        r.error = error;
        r.stepCount = history.size();
        r.toolHistory = new ArrayList<>(history);
        return r;
    }

    // getters and setters
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public boolean isDenied() { return denied; }
    public void setDenied(boolean denied) { this.denied = denied; }
    public String getDenyReason() { return denyReason; }
    public void setDenyReason(String denyReason) { this.denyReason = denyReason; }
    public long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }
    public int getStepCount() { return stepCount; }
    public void setStepCount(int stepCount) { this.stepCount = stepCount; }
    public List<String> getToolHistory() { return toolHistory; }
    public void setToolHistory(List<String> toolHistory) { this.toolHistory = toolHistory; }

}
