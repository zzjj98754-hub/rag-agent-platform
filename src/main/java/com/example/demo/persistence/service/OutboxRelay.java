package com.example.demo.persistence.service;

import com.example.demo.persistence.entity.OutboxEventEntity;
import com.example.demo.persistence.mapper.OutboxEventMapper;
import com.example.demo.service.ChatCacheProjector;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Polling relay for the local transactional outbox. */
@Service
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventMapper mapper;
    private final ChatCacheProjector projector;
    private final int batchSize;
    private final int maxRetries;
    private final long retryDelaySeconds;

    public OutboxRelay(
            OutboxEventMapper mapper,
            ChatCacheProjector projector,
            @Value("${app.outbox.batch-size}") int batchSize,
            @Value("${app.outbox.max-retries}") int maxRetries,
            @Value("${app.outbox.retry-delay-seconds}") long retryDelaySeconds) {
        this.mapper = mapper;
        this.projector = projector;
        this.batchSize = batchSize;
        this.maxRetries = maxRetries;
        this.retryDelaySeconds = retryDelaySeconds;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms}")
    public void relay() {
        List<OutboxEventEntity> events;
        try {
            events = mapper.findPending(batchSize);
        } catch (Exception e) {
            log.debug("Outbox polling skipped: {}", e.getMessage());
            return;
        }
        for (OutboxEventEntity event : events) {
            try {
                projector.project(event);
                mapper.markProcessed(event.getId());
            } catch (Exception e) {
                mapper.markRetry(event.getId(), e.getMessage(),
                        retryDelaySeconds, maxRetries);
                log.warn("Outbox projection failed | eventId={} retry={}: {}",
                        event.getId(), event.getRetryCount() + 1, e.getMessage());
            }
        }
    }
}
