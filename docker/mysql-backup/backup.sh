#!/bin/sh
# 每日 MySQL 全量备份 sidecar:mysqldump --single-transaction(InnoDB 一致快照)+ gzip,保留 7 天。
# 首次备份在容器启动 24h 后;如需立即验证:docker compose exec mysql-backup sh -c 'nohup sh /backup.sh &'
set -eu

INTERVAL_SECONDS=86400
RETENTION_DAYS=7

echo "[backup] starting, interval=${INTERVAL_SECONDS}s, retention=${RETENTION_DAYS}d, database=${MYSQL_DATABASE}"

while true; do
  TIMESTAMP=$(date +%Y%m%d_%H%M%S)
  TARGET="/backups/db_${TIMESTAMP}.sql.gz"
  echo "[backup] dumping to ${TARGET}"

  # 失败不退出(mysqldump 失败会返回非 0,单次失败跳过本轮,下个周期重试)
  if mysqldump -h mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" \
        --single-transaction --quick --routines --triggers \
        "${MYSQL_DATABASE}" | gzip > "${TARGET}"; then
    echo "[backup] done: ${TARGET} ($(du -h "${TARGET}" | cut -f1))"
  else
    echo "[backup] FAILED at ${TIMESTAMP}, will retry next cycle" >&2
  fi

  # 滚动清理:保留最近 N 天
  find /backups -name 'db_*.sql.gz' -mtime +${RETENTION_DAYS} -delete

  sleep "${INTERVAL_SECONDS}"
done
