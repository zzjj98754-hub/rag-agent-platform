package com.example.demo.service;

import com.example.demo.persistence.entity.OutboxEventEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Zero-infrastructure fallback used when Kafka is disabled. */
@Component
@ConditionalOnProperty(prefix = "app.index.kafka", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LocalIndexEventPublisher implements IndexEventPublisher {
    private final DocumentIndexTaskService tasks;
    public LocalIndexEventPublisher(DocumentIndexTaskService tasks) { this.tasks = tasks; }
    @Override public void publish(OutboxEventEntity event) { tasks.consumeJson(event.getPayload()); }
}
