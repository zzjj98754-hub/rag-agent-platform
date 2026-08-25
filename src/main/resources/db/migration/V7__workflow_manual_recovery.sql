ALTER TABLE `workflow_instance`
    ADD COLUMN `triggered_role` VARCHAR(32) NOT NULL DEFAULT 'USER' AFTER `triggered_by`;
