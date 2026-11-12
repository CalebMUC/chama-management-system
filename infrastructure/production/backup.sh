#!/usr/bin/env bash
# Backs up chama_db and the PostgreSQL roles (chama_app and chama_owner live outside the database; a restore needs them).
# Kept BACKUP_KEEP_DAYS here (default 14), copied with rclone to BACKUP_REMOTE (kept BACKUP_REMOTE_KEEP_DAYS, default 60).
# Run nightly from cron and before every release (deploy.sh does). Exits non-zero if anything fails, so cron mails the error.
#   crontab -e  ->  0 3 * * * /opt/chama/backend/infrastructure/production/backup.sh >> /opt/chama/backups/backup.log 2>&1
set -euo pipefail
# Dumps hold every chama's records: readable by the deploy user only.
umask 077

here="$(cd "$(dirname "$0")" && pwd)"
env_file="${CHAMA_ENV_FILE:-$here/../../../.env}"
setting() { grep -E "^$1=" "$env_file" | tail -n 1 | cut -d= -f2- || true; }

backup_dir="$(setting BACKUP_DIR)"; backup_dir="${backup_dir:-$here/../../../backups}"
keep_days="$(setting BACKUP_KEEP_DAYS)"; keep_days="${keep_days:-14}"
remote="$(setting BACKUP_REMOTE)"
remote_keep_days="$(setting BACKUP_REMOTE_KEEP_DAYS)"; remote_keep_days="${remote_keep_days:-60}"

mkdir -p "$backup_dir"
stamp="$(date -u +%Y-%m-%dT%H%M%SZ)"
dump="$backup_dir/chama-$stamp.dump"
roles="$backup_dir/chama-roles-$stamp.sql"

# Custom format (-Fc) is compressed and restores with pg_restore.
"$here/chama.sh" exec -T chama-db pg_dump -U postgres -Fc chama_db > "$dump.partial"
mv "$dump.partial" "$dump"
"$here/chama.sh" exec -T chama-db pg_dumpall -U postgres --roles-only > "$roles"
echo "$(date -u +%FT%TZ) dumped $(du -h "$dump" | cut -f1) to $dump"

if [[ -n "$remote" ]]; then
  rclone copy "$dump" "$remote/"
  rclone copy "$roles" "$remote/"
  rclone delete --min-age "${remote_keep_days}d" "$remote/"
  echo "$(date -u +%FT%TZ) copied to $remote"
else
  echo "$(date -u +%FT%TZ) WARNING: BACKUP_REMOTE is not set, so this backup exists only on this server." >&2
fi

find "$backup_dir" -maxdepth 1 -name 'chama-*' -mtime "+$keep_days" -delete
