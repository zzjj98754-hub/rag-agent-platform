package com.example.demo.persistence.service;

import com.example.demo.persistence.entity.OutboxEventEntity;
import com.example.demo.persistence.mapper.OutboxEventMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Writes an integration event in the same transaction as business data. */
@Service
public class OutboxEventService {

    public static final String SESSION_CREATED = "SESSION_CREATED";
    public static final String MESSAGE_APPENDED = "CHAT_MESSAGE_APPENDED";
    public static final String DOCUMENT_INDEX_REQUESTED = "DOCUMENT_INDEX_REQUESTED";

    private final OutboxEventMapper mapper;
    private final ObjectMapper objectMapper;

    public OutboxEventService(OutboxEventMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void sessionCreated(String sessionId, long timestamp) {
        append(sessionId, SESSION_CREATED, Map.of(
                "sessionId", sessionId,
                "timestamp", timestamp));
    }

    public void messageAppended(
            String sessionId,
            String role,
            String content,
            long timestamp) {
        append(sessionId, MESSAGE_APPENDED, Map.of(
                "sessionId", sessionId,
                "role", role,
                "content", content,
                "timestamp", timestamp));
    }

    /** Source text is kept in the outbox for this learning demo, avoiding shared file storage. */
    public void documentIndexRequested(String taskId, Long documentId, int documentVersion, String fileName, String filePath, String content, Long creatorId, String contentHash) {
        append(taskId, DOCUMENT_INDEX_REQUESTED, Map.of(
                "taskId", taskId, "documentId", documentId, "documentVersion", documentVersion,
                "contentHash", contentHash, "fileName", fileName, "filePath", filePath, "content", content,
                "creatorId", creatorId == null ? -1L : creatorId));
    }

    private void append(String sessionId, String eventType, Object payload) {
        OutboxEventEntity event = new OutboxEventEntity();
        event.setAggregateType(DOCUMENT_INDEX_REQUESTED.equals(eventType) ? "DOCUMENT" : "CHAT_SESSION");
        event.setAggregateId(sessionId);
        event.setEventType(eventType);
        try {
            event.setPayload(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox event serialization failed", e);
        }
        mapper.insert(event);
    }
}
