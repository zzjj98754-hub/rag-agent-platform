package com.example.demo.service;

import com.example.demo.persistence.entity.OutboxEventEntity;

/** Publishes a durable index command after the Outbox transaction commits. */
public interface IndexEventPublisher {
    void publish(OutboxEventEntity event);
}
