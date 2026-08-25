#!/usr/bin/env sh
set -eu
: "${TLS_DOMAIN:?TLS_DOMAIN is required}"
: "${TLS_EMAIL:?TLS_EMAIL is required}"
docker compose -f docker-compose.yml -f docker-compose.prod.yml stop nginx >/dev/null 2>&1 || true
docker compose -f docker-compose.yml -f docker-compose.prod.yml run --rm --publish 80:80 \
  --entrypoint certbot certbot certonly --standalone \
  --cert-name demo00 -d "$TLS_DOMAIN" --email "$TLS_EMAIL" --agree-tos --no-eff-email
