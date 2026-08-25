ALTER TABLE `mcp_server`
    ADD COLUMN `connection_status` VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN' AFTER `enabled`,
    ADD COLUMN `last_error` VARCHAR(2000) NULL AFTER `connection_status`,
    ADD COLUMN `last_connected_at` DATETIME(3) NULL AFTER `last_error`,
    ADD COLUMN `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) AFTER `create_time`;

CREATE INDEX `idx_mcp_server_enabled` ON `mcp_server` (`enabled`);

ALTER TABLE `audit_log`
    ADD COLUMN `outcome` VARCHAR(32) NULL AFTER `action`,
    ADD COLUMN `elapsed_ms` BIGINT NULL AFTER `outcome`;
