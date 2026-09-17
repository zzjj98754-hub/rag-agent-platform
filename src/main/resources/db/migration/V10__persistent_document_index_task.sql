ALTER TABLE `document`
    ADD COLUMN `content` LONGTEXT NULL,
    ADD COLUMN `content_hash` CHAR(64) NULL,
    ADD COLUMN `document_version` INT NOT NULL DEFAULT 1;

CREATE TABLE `index_task` (
    `task_id` VARCHAR(64) NOT NULL,
    `document_id` BIGINT NOT NULL,
    `document_version` INT NOT NULL,
    `content_hash` CHAR(64) NOT NULL,
    `status` VARCHAR(16) NOT NULL,
    `retry_count` INT NOT NULL DEFAULT 0,
    `max_retries` INT NOT NULL DEFAULT 3,
    `next_retry_time` DATETIME(3) NULL,
    `worker_id` VARCHAR(128) NULL,
    `lease_expire_time` DATETIME(3) NULL,
    `failure_code` VARCHAR(64) NULL,
    `failure_reason` VARCHAR(1000) NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `started_at` DATETIME(3) NULL,
    `finished_at` DATETIME(3) NULL,
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`task_id`),
    UNIQUE KEY `uk_index_task_document_version` (`document_id`, `document_version`),
    KEY `idx_index_task_status_retry` (`status`, `next_retry_time`),
    CONSTRAINT `fk_index_task_document` FOREIGN KEY (`document_id`) REFERENCES `document` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
