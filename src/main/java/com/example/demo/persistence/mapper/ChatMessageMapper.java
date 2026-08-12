package com.example.demo.persistence.mapper;

import com.example.demo.persistence.entity.ChatMessageEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ChatMessageMapper {

    int insert(ChatMessageEntity message);

    List<ChatMessageEntity> findRecentBySessionId(
            @Param("sessionId") Long sessionId,
            @Param("limit") int limit);

    List<ChatMessageEntity> findAllBySessionId(@Param("sessionId") Long sessionId);

    long countBySessionId(@Param("sessionId") Long sessionId);
}
