package com.example.demo.persistence.mapper;

import com.example.demo.persistence.entity.IndexTaskEntity;
import org.apache.ibatis.annotations.Param;

public interface IndexTaskMapper {
    int insert(IndexTaskEntity task);
    IndexTaskEntity findById(@Param("taskId") String taskId);
    int claim(@Param("taskId") String taskId, @Param("workerId") String workerId, @Param("leaseSeconds") int leaseSeconds);
    int finish(@Param("taskId") String taskId, @Param("status") String status, @Param("failureCode") String failureCode, @Param("failureReason") String failureReason);
    int retry(@Param("taskId") String taskId);
}
