package com.example.demo.persistence.mapper;

import com.example.demo.persistence.entity.OutboxEventEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface OutboxEventMapper {
    int insert(OutboxEventEntity event);
    List<OutboxEventEntity> findPending(@Param("limit") int limit);
    int markProcessed(@Param("id") long id);
    int markRetry(@Param("id") long id,
                  @Param("error") String error,
                  @Param("delaySeconds") long delaySeconds,
                  @Param("maxRetries") int maxRetries);
}
