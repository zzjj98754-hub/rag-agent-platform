CREATE TABLE IF NOT EXISTS `printer_product` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `product_code` VARCHAR(64) NOT NULL,
    `model_name` VARCHAR(128) NOT NULL,
    `description` VARCHAR(500) NOT NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_printer_product_code` (`product_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `printer_product_document` (
    `product_id` BIGINT NOT NULL,
    `document_id` BIGINT NOT NULL,
    `document_version` INT NOT NULL DEFAULT 1,
    PRIMARY KEY (`product_id`, `document_id`),
    CONSTRAINT `fk_printer_product_document_product` FOREIGN KEY (`product_id`)
        REFERENCES `printer_product` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_printer_product_document_document` FOREIGN KEY (`document_id`)
        REFERENCES `document` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `after_sales_application` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `application_no` VARCHAR(32) NOT NULL,
    `user_id` BIGINT NOT NULL,
    `product_id` BIGINT NOT NULL,
    `question` VARCHAR(2000) NOT NULL,
    `troubleshooting_steps` LONGTEXT NOT NULL,
    `additional_note` VARCHAR(2000) NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    `processing_note` VARCHAR(2000) NULL,
    `idempotency_key` VARCHAR(128) NOT NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_after_sales_application_no` (`application_no`),
    UNIQUE KEY `uk_after_sales_user_idempotency` (`user_id`, `idempotency_key`),
    KEY `idx_after_sales_user_update` (`user_id`, `update_time`),
    KEY `idx_after_sales_status_update` (`status`, `update_time`),
    CONSTRAINT `fk_after_sales_user` FOREIGN KEY (`user_id`)
        REFERENCES `user` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_after_sales_product` FOREIGN KEY (`product_id`)
        REFERENCES `printer_product` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
