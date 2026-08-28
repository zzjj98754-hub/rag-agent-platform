#!/bin/bash
# 生成 Grafana nginx basic_auth 凭据文件(第一道门)。
# 用法:
#   GRAFANA_BASIC_AUTH_USER=admin GRAFANA_BASIC_AUTH_PASSWORD='<密码>' bash scripts/gen-grafana-htpasswd.sh
#   或先写入 .env.prod,再以 --env-file 方式: export $(grep -E '^GRAFANA_BASIC_AUTH' .env.prod | xargs) && bash scripts/gen-grafana-htpasswd.sh
# 产出 docker/nginx/.htpasswd(已被 .gitignore 排除,prod overlay 中 ro 挂载)。
set -eu

USER="${GRAFANA_BASIC_AUTH_USER:-admin}"
PASSWORD="${GRAFANA_BASIC_AUTH_PASSWORD:-}"

if [ -z "${PASSWORD}" ]; then
  echo "错误:GRAFANA_BASIC_AUTH_PASSWORD 未设置" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="${SCRIPT_DIR}/../docker/nginx/.htpasswd"

HASH=$(printf '%s' "${PASSWORD}" | openssl passwd -apr1 -stdin)
printf '%s:%s\n' "${USER}" "${HASH}" > "${TARGET}"
chmod 600 "${TARGET}"

echo "已生成 ${TARGET} (user=${USER})"
