ALTER TABLE `workflow_definition`
    ADD COLUMN `owner_id` BIGINT NULL AFTER `enabled`,
    ADD COLUMN `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) AFTER `create_time`;

ALTER TABLE `workflow_instance`
    ADD COLUMN `owner_id` BIGINT NULL AFTER `triggered_by`,
    ADD COLUMN `lock_version` BIGINT NOT NULL DEFAULT 0 AFTER `status`,
    ADD COLUMN `next_retry_at` DATETIME(3) NULL AFTER `current_node`,
    ADD COLUMN `error` VARCHAR(2000) NULL AFTER `output`;

ALTER TABLE `workflow_step_execution`
    ADD COLUMN `execution_key` VARCHAR(191) NOT NULL AFTER `node_id`,
    ADD COLUMN `max_retries` INT NOT NULL DEFAULT 0 AFTER `retry_count`,
    ADD COLUMN `next_retry_at` DATETIME(3) NULL AFTER `max_retries`,
    ADD COLUMN `error` VARCHAR(2000) NULL AFTER `status`,
    ADD UNIQUE KEY `uk_workflow_step_execution_key` (`execution_key`);

CREATE INDEX `idx_workflow_instance_recovery`
    ON `workflow_instance` (`status`, `next_retry_at`);
