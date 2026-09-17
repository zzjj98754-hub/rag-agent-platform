package com.example.demo.service;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.index.kafka", name = "enabled", havingValue = "true")
public class KafkaDocumentIndexConsumer {
    private final DocumentIndexTaskService tasks;
    public KafkaDocumentIndexConsumer(DocumentIndexTaskService tasks) { this.tasks = tasks; }
    @KafkaListener(topics = "${app.index.kafka.topic}", groupId = "${spring.application.name}-indexer")
    public void consume(ConsumerRecord<String, String> record) {
        TextMapGetter<ConsumerRecord<String, String>> getter = new TextMapGetter<>() {
            @Override public Iterable<String> keys(ConsumerRecord<String, String> source) {
                return java.util.stream.StreamSupport.stream(source.headers().headers("traceparent").spliterator(), false)
                        .map(header -> "traceparent").distinct().toList();
            }
            @Override public String get(ConsumerRecord<String, String> source, String key) {
                var header = source.headers().lastHeader(key);
                return header == null ? null : new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
            }
        };
        Context parent = GlobalOpenTelemetry.getPropagators().getTextMapPropagator().extract(Context.current(), record, getter);
        try (var scope = parent.makeCurrent()) {
            tasks.consumeJson(record.value());
        }
    }
}
