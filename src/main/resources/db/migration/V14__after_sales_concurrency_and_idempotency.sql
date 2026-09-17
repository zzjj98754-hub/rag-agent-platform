ALTER TABLE `after_sales_application`
    ADD COLUMN `request_fingerprint` CHAR(64) NULL AFTER `idempotency_key`,
    ADD COLUMN `version` INT NOT NULL DEFAULT 0 AFTER `status`;

CREATE TABLE IF NOT EXISTS `after_sales_status_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `application_id` BIGINT NOT NULL,
    `from_status` VARCHAR(16) NULL,
    `to_status` VARCHAR(16) NOT NULL,
    `operator_id` BIGINT NOT NULL,
    `note` VARCHAR(2000) NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_after_sales_event_application` (`application_id`, `id`),
    CONSTRAINT `fk_after_sales_event_application` FOREIGN KEY (`application_id`)
        REFERENCES `after_sales_application` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
