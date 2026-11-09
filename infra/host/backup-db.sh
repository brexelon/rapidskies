#!/usr/bin/env bash
# Nightly MariaDB dump + offsite copy (plan §3, Backups). Runs on the host, not in a container.
#
# Install:
#   sudo install -m 750 infra/host/backup-db.sh /usr/local/sbin/rapidskies-backup-db
#   sudo install -m 600 infra/host/backup-db.env.example /etc/rapidskies/backup-db.env   # then edit
#   sudo install -m 644 infra/host/rapidskies-backup-db.{service,timer} /etc/systemd/system/
#   sudo systemctl daemon-reload && sudo systemctl enable --now rapidskies-backup-db.timer
#
# Also copies the newest Pterodactyl (Wings) backups offsite, so game-server backups
# survive a lost disk. Needs: mariadb-dump (or mysqldump), gzip, rclone with a remote
# configured for Backblaze B2 or S3 (`rclone config`).
set -euo pipefail

ENV_FILE="${ENV_FILE:-/etc/rapidskies/backup-db.env}"
# shellcheck source=/dev/null
source "$ENV_FILE"

: "${DB_HOST:=127.0.0.1}" "${DB_PORT:=3306}" "${DB_USER:?}" "${DB_PASSWORD:?}"
: "${LOCAL_DIR:=/var/backups/rapidskies}" "${KEEP_LOCAL_DAYS:=7}"
: "${RCLONE_REMOTE:?}"                       # e.g. b2:rapidskies-backups
: "${WINGS_BACKUP_DIR:=/var/lib/pterodactyl/backups}"
: "${KEEP_REMOTE_DAYS:=30}"

DUMP_BIN="$(command -v mariadb-dump || command -v mysqldump)"
CLIENT_BIN="$(command -v mariadb || command -v mysql)"
STAMP="$(date -u +%Y-%m-%dT%H%MZ)"
OUT="$LOCAL_DIR/db"
mkdir -p "$OUT"

# Credentials go through a temp defaults file so they never show up in `ps`.
CNF="$(mktemp)"
trap 'rm -f "$CNF"' EXIT
printf '[client]\nhost=%s\nport=%s\nuser=%s\npassword=%s\n' "$DB_HOST" "$DB_PORT" "$DB_USER" "$DB_PASSWORD" > "$CNF"

# One file per database, so a single schema (e.g. luckperms) can be restored alone.
DATABASES="$("$CLIENT_BIN" --defaults-extra-file="$CNF" -N -e 'SHOW DATABASES' \
  | grep -Ev '^(information_schema|performance_schema|mysql|sys)$')"
for db in $DATABASES; do
  "$DUMP_BIN" --defaults-extra-file="$CNF" --single-transaction --quick --routines --triggers \
    --events --hex-blob "$db" | gzip -6 > "$OUT/${db}_${STAMP}.sql.gz"
done

find "$OUT" -name '*.sql.gz' -mtime +"$KEEP_LOCAL_DAYS" -delete

# Offsite: database dumps, plus Wings' server backups (only new files are uploaded).
rclone copy "$OUT" "$RCLONE_REMOTE/db"
if [ -d "$WINGS_BACKUP_DIR" ]; then
  # --min-age skips archives Wings is still writing.
  rclone copy "$WINGS_BACKUP_DIR" "$RCLONE_REMOTE/wings" --min-age 5m
fi
rclone delete "$RCLONE_REMOTE" --min-age "${KEEP_REMOTE_DAYS}d"

echo "backup-db: done ($STAMP, $(echo "$DATABASES" | wc -w) databases)"
