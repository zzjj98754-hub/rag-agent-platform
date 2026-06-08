package com.example.demo.dto;

/**
 * 异步入库任务状态 —— 用于轮询入库进度。
 */
public class IngestionStatus {

    public enum State { PENDING, RUNNING, COMPLETED, FAILED }

    private final String taskId;
    private volatile State state;
    private volatile int processed;
    private volatile int total;
    private volatile IngestionResult result;
    private volatile String error;
    private final long createdAt;
    private volatile long completedAt;

    public IngestionStatus(String taskId) {
        this.taskId = taskId;
        this.state = State.PENDING;
        this.createdAt = System.currentTimeMillis();
    }

    // ---- state transitions ----

    public void start(int total) {
        this.state = State.RUNNING;
        this.total = total;
    }

    public void progress(int processed) {
        this.processed = processed;
    }

    public void complete(IngestionResult result) {
        this.state = State.COMPLETED;
        this.result = result;
        this.processed = total;
        this.completedAt = System.currentTimeMillis();
    }

    public void fail(String error) {
        this.state = State.FAILED;
        this.error = error;
        this.completedAt = System.currentTimeMillis();
    }

    // ---- getters ----

    public String getTaskId() { return taskId; }
    public State getState() { return state; }
    public int getProcessed() { return processed; }
    public int getTotal() { return total; }
    public IngestionResult getResult() { return result; }
    public String getError() { return error; }
    public long getCreatedAt() { return createdAt; }
    public long getCompletedAt() { return completedAt; }

    /** 已用时间（毫秒），如果未完成则计算到当前时间 */
    public long getElapsedMs() {
        long end = completedAt > 0 ? completedAt : System.currentTimeMillis();
        return end - createdAt;
    }
}
