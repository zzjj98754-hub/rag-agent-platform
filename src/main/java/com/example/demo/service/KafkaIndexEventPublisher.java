package com.example.demo.service;

import com.example.demo.persistence.entity.OutboxEventEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.apache.kafka.clients.producer.ProducerRecord;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;

/** Kafka transport is opt-in; the message is the outbox payload and remains idempotent by document hash. */
@Component
@ConditionalOnProperty(prefix = "app.index.kafka", name = "enabled", havingValue = "true")
public class KafkaIndexEventPublisher implements IndexEventPublisher {
    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    public KafkaIndexEventPublisher(KafkaTemplate<String, String> kafka,
            @org.springframework.beans.factory.annotation.Value("${app.index.kafka.topic}") String topic) {
        this.kafka = kafka; this.topic = topic;
    }
    @Override public void publish(OutboxEventEntity event) {
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, event.getAggregateId(), event.getPayload());
            GlobalOpenTelemetry.getPropagators().getTextMapPropagator().inject(
                    Context.current(), record, (target, key, value) -> target.headers().add(key, value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            kafka.send(record).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException("Kafka index event publish failed", ex);
        }
    }
}
