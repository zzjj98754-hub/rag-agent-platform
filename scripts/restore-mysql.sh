#!/usr/bin/env sh
set -eu
: "${1:?usage: restore-mysql.sh backup.sql.gz}"
test -f "$1"
gzip -dc "$1" | docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  exec -T mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD:?}" "${MYSQL_DATABASE:-demo00}"
