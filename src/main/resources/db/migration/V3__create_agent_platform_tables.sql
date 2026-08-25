ALTER TABLE `user`
    MODIFY COLUMN `role` VARCHAR(32) NOT NULL DEFAULT 'USER';

ALTER TABLE `chat_session`
    ADD COLUMN `agent_state` VARCHAR(32) NOT NULL DEFAULT 'IDLE' AFTER `title`,
    ADD COLUMN `agent_state_detail` VARCHAR(1000) NULL AFTER `agent_state`;

CREATE TABLE IF NOT EXISTS `mcp_server` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(128) NOT NULL,
    `transport` VARCHAR(32) NOT NULL,
    `command` VARCHAR(512) NULL,
    `command_args` JSON NULL,
    `url` VARCHAR(1024) NULL,
    `auth_header_enc` VARCHAR(2048) NULL,
    `enabled` BOOLEAN NOT NULL DEFAULT TRUE,
    `created_by` BIGINT NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_mcp_server_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `skill` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `code` VARCHAR(64) NOT NULL,
    `name` VARCHAR(255) NOT NULL,
    `description` VARCHAR(1000) NULL,
    `current_version` INT NOT NULL DEFAULT 0,
    `enabled` BOOLEAN NOT NULL DEFAULT TRUE,
    `allowed_roles` VARCHAR(255) NOT NULL DEFAULT 'USER,ANALYST,ADMIN',
    `owner` BIGINT NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_skill_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `skill_version` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `skill_id` BIGINT NOT NULL,
    `version` INT NOT NULL,
    `prompt_template` LONGTEXT NOT NULL,
    `tool_refs` JSON NULL,
    `change_log` VARCHAR(2000) NULL,
    `created_by` BIGINT NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_skill_version` (`skill_id`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `workflow_definition` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `code` VARCHAR(64) NOT NULL,
    `version` INT NOT NULL,
    `dsl` JSON NOT NULL,
    `enabled` BOOLEAN NOT NULL DEFAULT TRUE,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_workflow_definition_version` (`code`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `workflow_instance` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `instance_id` VARCHAR(64) NOT NULL,
    `definition_id` BIGINT NOT NULL,
    `version` INT NOT NULL,
    `status` VARCHAR(32) NOT NULL,
    `current_node` VARCHAR(128) NULL,
    `input` JSON NULL,
    `output` JSON NULL,
    `triggered_by` BIGINT NULL,
    `session_id` VARCHAR(64) NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_workflow_instance_id` (`instance_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `workflow_step_execution` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `instance_id` VARCHAR(64) NOT NULL,
    `node_id` VARCHAR(128) NOT NULL,
    `node_type` VARCHAR(32) NOT NULL,
    `input` JSON NULL,
    `output` JSON NULL,
    `status` VARCHAR(32) NOT NULL,
    `retry_count` INT NOT NULL DEFAULT 0,
    `started_time` DATETIME(3) NULL,
    `finished_time` DATETIME(3) NULL,
    PRIMARY KEY (`id`),
    KEY `idx_workflow_step_instance` (`instance_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `conversation_summary` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `session_id` VARCHAR(64) NOT NULL,
    `summary` LONGTEXT NOT NULL,
    `start_message_id` BIGINT NULL,
    `end_message_id` BIGINT NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conversation_summary_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `audit_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NULL,
    `action` VARCHAR(128) NOT NULL,
    `resource_type` VARCHAR(64) NULL,
    `resource_id` VARCHAR(128) NULL,
    `detail` JSON NULL,
    `ip` VARCHAR(64) NULL,
    `trace_id` VARCHAR(128) NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_audit_created_at` (`created_at`),
    KEY `idx_audit_user_id` (`user_id`),
    KEY `idx_audit_trace_id` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
