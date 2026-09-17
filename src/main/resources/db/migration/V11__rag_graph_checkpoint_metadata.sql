ALTER TABLE `rag_graph_run`
    ADD COLUMN `current_node` VARCHAR(64) NULL,
    ADD COLUMN `session_id` VARCHAR(64) NULL,
    ADD COLUMN `risk_level` VARCHAR(16) NULL,
    ADD COLUMN `pending_approval` BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN `version` BIGINT NOT NULL DEFAULT 0;

CREATE INDEX `idx_rag_graph_owner_status` ON `rag_graph_run` (`owner_id`, `status`);
