#!/usr/bin/env sh
set -eu

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <previous-image-reference>" >&2
  exit 2
fi

APP_IMAGE="$1" docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  up -d --no-build app nginx
