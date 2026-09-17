CREATE TABLE IF NOT EXISTS `document_chunk` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `document_id` BIGINT NOT NULL,
    `chunk_key` VARCHAR(512) NOT NULL,
    `parent_chunk_key` VARCHAR(512) NOT NULL,
    `chunk_text` LONGTEXT NOT NULL,
    `parent_text` LONGTEXT NOT NULL,
    `sequence_no` INT NOT NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_document_chunk_key` (`document_id`, `chunk_key`),
    KEY `idx_document_chunk_parent` (`document_id`, `parent_chunk_key`),
    CONSTRAINT `fk_document_chunk_document` FOREIGN KEY (`document_id`)
      REFERENCES `document` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
