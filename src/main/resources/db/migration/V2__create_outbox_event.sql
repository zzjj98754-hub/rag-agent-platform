CREATE TABLE IF NOT EXISTS `outbox_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `aggregate_type` VARCHAR(64) NOT NULL,
    `aggregate_id` VARCHAR(128) NOT NULL,
    `event_type` VARCHAR(64) NOT NULL,
    `payload` JSON NOT NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    `retry_count` INT NOT NULL DEFAULT 0,
    `next_retry_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `last_error` VARCHAR(1000) NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `processed_time` DATETIME(3) NULL,
    PRIMARY KEY (`id`),
    KEY `idx_outbox_pending` (`status`, `next_retry_time`, `id`),
    KEY `idx_outbox_aggregate` (`aggregate_type`, `aggregate_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
