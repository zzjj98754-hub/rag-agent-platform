ALTER TABLE `outbox_event`
    ADD COLUMN `claimed_by` VARCHAR(128) NULL,
    ADD COLUMN `claim_until` DATETIME(3) NULL,
    ADD KEY `idx_outbox_claim` (`status`, `claim_until`, `id`);
