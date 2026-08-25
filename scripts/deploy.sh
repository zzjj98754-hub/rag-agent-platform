#!/usr/bin/env sh
set -eu
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  --env-file .env.prod --profile prod --profile tls up -d --build --remove-orphans
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps
