ALTER TABLE `skill`
    ADD COLUMN `parameter_schema` JSON NULL AFTER `description`,
    ADD COLUMN `publish_status` VARCHAR(32) NOT NULL DEFAULT 'DRAFT' AFTER `current_version`,
    ADD COLUMN `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) AFTER `create_time`;

ALTER TABLE `skill_version`
    ADD COLUMN `parameter_schema` JSON NULL AFTER `prompt_template`,
    ADD COLUMN `status` VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED' AFTER `change_log`,
    ADD COLUMN `rollback_from_version` INT NULL AFTER `status`;

ALTER TABLE `conversation_summary`
    ADD COLUMN `message_count` INT NOT NULL DEFAULT 0 AFTER `summary`,
    ADD COLUMN `token_count` INT NOT NULL DEFAULT 0 AFTER `message_count`,
    ADD COLUMN `model_name` VARCHAR(255) NULL AFTER `token_count`;
