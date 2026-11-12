#!/usr/bin/env bash
# Releases what is on main: backs up the database, pulls both repositories, builds one image at a time, restarts
# (Flyway migrates at API start-up), refreshes Caddy's site block if it changed, then checks health.
# Run it on the server: /opt/chama/backend/infrastructure/production/deploy.sh   (the API is down for its start-up, 30-60 s)
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../.." && pwd)"                      # /opt/chama
env_file="${CHAMA_ENV_FILE:-$root/.env}"
site="$(grep -E '^CHAMA_ADDRESS=' "$env_file" | tail -n 1 | cut -d= -f2-)"
pos_deploy="${POS_DEPLOY_DIR:-/home/deploy/ultimatepos/backend/deploy}"
caddy_sites="${CADDY_SITES_DIR:-/home/deploy/ultimatepos/caddy-sites}"

echo "== 1/6 Backing up the database"
"$here/backup.sh"

echo "== 2/6 Pulling the latest main"
git -C "$root/backend" pull --ff-only
git -C "$root/frontend" pull --ff-only

echo "== 3/6 Building the images"
# One at a time: the POS runs on the same server.
"$here/chama.sh" build chama-api
"$here/chama.sh" build chama-web

echo "== 4/6 Restarting"
"$here/chama.sh" up -d --remove-orphans

echo "== 5/6 Caddy site block"
if ! cmp -s "$here/chama.caddy" "$caddy_sites/chama.caddy"; then
  cp "$here/chama.caddy" "$caddy_sites/chama.caddy"
  "$pos_deploy/pos.sh" exec -T caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile
  echo "Reloaded Caddy with the new chama.caddy."
else
  echo "Unchanged."
fi

echo "== 6/6 Checking https://$site"
for attempt in $(seq 1 60); do
  if curl -fsS "https://$site/actuator/health" > /dev/null 2>&1; then
    echo "Released. https://$site is up."
    exit 0
  fi
  sleep 3
done
echo "The API did not answer https://$site/actuator/health within 3 minutes. Check: $here/chama.sh logs --tail 200 chama-api" >&2
exit 1
