CREATE TABLE IF NOT EXISTS `rag_graph_run` (
  `run_id` VARCHAR(64) NOT NULL,
  `owner_id` BIGINT NOT NULL,
  `status` VARCHAR(32) NOT NULL,
  `state` JSON NOT NULL,
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`run_id`), KEY `idx_rag_graph_status` (`status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
