package com.example.demo.persistence.mapper;

import com.example.demo.persistence.entity.ChatSessionEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ChatSessionMapper {

    int upsert(ChatSessionEntity session);

    ChatSessionEntity findBySessionId(@Param("sessionId") String sessionId);

    List<ChatSessionEntity> findByUserId(
            @Param("userId") Long userId,
            @Param("limit") int limit);

    int updateTitle(
            @Param("sessionId") String sessionId,
            @Param("title") String title);

    int touch(@Param("id") Long id);
}
