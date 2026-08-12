package com.example.demo.persistence.entity;

import java.time.LocalDateTime;

public class OutboxEventEntity {
    private Long id;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    private String payload;
    private String status;
    private int retryCount;
    private LocalDateTime nextRetryTime;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime processedTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAggregateType() { return aggregateType; }
    public void setAggregateType(String aggregateType) { this.aggregateType = aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public LocalDateTime getNextRetryTime() { return nextRetryTime; }
    public void setNextRetryTime(LocalDateTime nextRetryTime) { this.nextRetryTime = nextRetryTime; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getProcessedTime() { return processedTime; }
    public void setProcessedTime(LocalDateTime processedTime) { this.processedTime = processedTime; }
}
