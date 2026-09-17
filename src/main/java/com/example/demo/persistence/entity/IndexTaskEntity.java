package com.example.demo.persistence.entity;

import java.time.LocalDateTime;

public class IndexTaskEntity {
    private String taskId;
    private Long documentId;
    private int documentVersion;
    private String contentHash;
    private String status;
    private int retryCount;
    private int maxRetries;
    private LocalDateTime nextRetryTime;
    private String workerId;
    private LocalDateTime leaseExpireTime;
    private String failureCode;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime updatedAt;
    public String getTaskId(){return taskId;} public void setTaskId(String v){taskId=v;}
    public Long getDocumentId(){return documentId;} public void setDocumentId(Long v){documentId=v;}
    public int getDocumentVersion(){return documentVersion;} public void setDocumentVersion(int v){documentVersion=v;}
    public String getContentHash(){return contentHash;} public void setContentHash(String v){contentHash=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public int getRetryCount(){return retryCount;} public void setRetryCount(int v){retryCount=v;}
    public int getMaxRetries(){return maxRetries;} public void setMaxRetries(int v){maxRetries=v;}
    public LocalDateTime getNextRetryTime(){return nextRetryTime;} public void setNextRetryTime(LocalDateTime v){nextRetryTime=v;}
    public String getWorkerId(){return workerId;} public void setWorkerId(String v){workerId=v;}
    public LocalDateTime getLeaseExpireTime(){return leaseExpireTime;} public void setLeaseExpireTime(LocalDateTime v){leaseExpireTime=v;}
    public String getFailureCode(){return failureCode;} public void setFailureCode(String v){failureCode=v;}
    public String getFailureReason(){return failureReason;} public void setFailureReason(String v){failureReason=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getStartedAt(){return startedAt;} public void setStartedAt(LocalDateTime v){startedAt=v;}
    public LocalDateTime getFinishedAt(){return finishedAt;} public void setFinishedAt(LocalDateTime v){finishedAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
