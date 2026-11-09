#!/usr/bin/env bash
# Nightly MariaDB dump + offsite copy (plan §3, Backups). Runs on a host, not in a container.
#
# The panel and the game node are separate machines, so install this on each one that holds
# backup data, with its own env file (see infra/README.md → Backups):
#   - game node:    WINGS_BACKUP_DIR set; DB_* if MariaDB runs there, else DUMP_DATABASES=false
#   - panel server: DB_* for the local MariaDB (panel database, plus game databases if they're here)
#
# Install:
#   sudo install -m 750 infra/host/backup-db.sh /usr/local/sbin/rapidskies-backup-db
#   sudo install -D -m 600 infra/host/backup-db.env.example /etc/rapidskies/backup-db.env   # then edit
#   sudo install -m 644 infra/host/rapidskies-backup-db.{service,timer} /etc/systemd/system/
#   sudo systemctl daemon-reload && sudo systemctl enable --now rapidskies-backup-db.timer
#
# Needs: rclone with a remote configured for Backblaze B2 or S3 (`rclone config`), plus
# mariadb-dump (or mysqldump) and gzip when dumping databases.
set -euo pipefail

ENV_FILE="${ENV_FILE:-/etc/rapidskies/backup-db.env}"
# shellcheck source=/dev/null
source "$ENV_FILE"

: "${RCLONE_REMOTE:?}"            # per machine, e.g. b2:rapidskies-backups/node
: "${KEEP_REMOTE_DAYS:=30}"
: "${DUMP_DATABASES:=true}"
: "${WINGS_BACKUP_DIR:=}"         # only on the game node: /var/lib/pterodactyl/backups
: "${LOCAL_DIR:=/var/backups/rapidskies}" "${KEEP_LOCAL_DAYS:=7}"

STAMP="$(date -u +%Y-%m-%dT%H%MZ)"
SUMMARY=()

if [ "$DUMP_DATABASES" = "true" ]; then
  : "${DB_HOST:=127.0.0.1}" "${DB_PORT:=3306}" "${DB_USER:?}" "${DB_PASSWORD:?}"
  DUMP_BIN="$(command -v mariadb-dump || command -v mysqldump)"
  CLIENT_BIN="$(command -v mariadb || command -v mysql)"
  OUT="$LOCAL_DIR/db"
  mkdir -p "$OUT"

  # Credentials go through a temp defaults file so they never show up in `ps`.
  CNF="$(mktemp)"
  trap 'rm -f "$CNF"' EXIT
  printf '[client]\nhost=%s\nport=%s\nuser=%s\npassword=%s\n' "$DB_HOST" "$DB_PORT" "$DB_USER" "$DB_PASSWORD" > "$CNF"

  # One file per database, so a single schema (e.g. luckperms, panel) can be restored alone.
  DATABASES="$("$CLIENT_BIN" --defaults-extra-file="$CNF" -N -e 'SHOW DATABASES' \
    | grep -Ev '^(information_schema|performance_schema|mysql|sys)$')"
  for db in $DATABASES; do
    "$DUMP_BIN" --defaults-extra-file="$CNF" --single-transaction --quick --routines --triggers \
      --events --hex-blob "$db" | gzip -6 > "$OUT/${db}_${STAMP}.sql.gz"
  done
  find "$OUT" -name '*.sql.gz' -mtime +"$KEEP_LOCAL_DAYS" -delete

  rclone copy "$OUT" "$RCLONE_REMOTE/db"
  SUMMARY+=("$(echo "$DATABASES" | wc -w) databases")
fi

if [ -n "$WINGS_BACKUP_DIR" ]; then
  if [ ! -d "$WINGS_BACKUP_DIR" ]; then
    echo "backup-db: WINGS_BACKUP_DIR $WINGS_BACKUP_DIR does not exist" >&2
    exit 1
  fi
  # Only new archives are uploaded; --min-age skips ones Wings is still writing.
  rclone copy "$WINGS_BACKUP_DIR" "$RCLONE_REMOTE/wings" --min-age 5m
  SUMMARY+=("wings backups")
fi

if [ "${#SUMMARY[@]}" -eq 0 ]; then
  echo "backup-db: nothing to do (DUMP_DATABASES=false and no WINGS_BACKUP_DIR)" >&2
  exit 1
fi

# Retention. Scoped to this machine's RCLONE_REMOTE path, so machines never prune each other.
rclone delete "$RCLONE_REMOTE" --min-age "${KEEP_REMOTE_DAYS}d"

echo "backup-db: done ($STAMP: ${SUMMARY[*]})"
