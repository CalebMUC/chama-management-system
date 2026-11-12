#!/usr/bin/env bash
# docker compose for the Chama stack, with the right compose file and .env. Any docker compose command works:
#   ./chama.sh ps | ./chama.sh up -d | ./chama.sh logs -f chama-api | ./chama.sh restart chama-api
# The .env file is /opt/chama/.env (three folders up from this one) unless CHAMA_ENV_FILE names another.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
export CHAMA_ENV_FILE="${CHAMA_ENV_FILE:-$here/../../../.env}"

if [[ ! -f "$CHAMA_ENV_FILE" ]]; then
  echo "No .env at $CHAMA_ENV_FILE. Copy infrastructure/production/.env.example there and fill it in." >&2
  exit 1
fi

exec docker compose -f "$here/docker-compose.prod.yml" --env-file "$CHAMA_ENV_FILE" "$@"
