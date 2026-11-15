#!/usr/bin/env bash
# Usage: restore.sh <object key> <postgresql://user@host:5432/dbname> [--allow-prod-name]
# Downloads a backup with the READ key, decrypts it and pg_restores it into an EMPTY database (T-044, runbook in
# docs/DEPLOYMENT.md §6). Needs R2_BACKUP_READ_ACCESS_KEY_ID / R2_BACKUP_READ_SECRET_ACCESS_KEY (operator only, passed
# with `-e`, never in the always-on container), BACKUP_ENCRYPTION_PASSPHRASE, and PGPASSWORD for the target.
set -euo pipefail

key="${1:?usage: restore.sh <object key> <target db url> [--allow-prod-name]}"
target="${2:?usage: restore.sh <object key> <target db url> [--allow-prod-name]}"
allow="${3:-}"
: "${R2_ENDPOINT:?}" "${R2_BACKUP_BUCKET:?}" "${BACKUP_ENCRYPTION_PASSPHRASE:?}"
: "${R2_BACKUP_READ_ACCESS_KEY_ID:?}" "${R2_BACKUP_READ_SECRET_ACCESS_KEY:?}"

[[ "$key" =~ ^[a-z0-9-]+/[0-9TZ-]+\.dump\.gpg$ ]] || { echo "restore: invalid object key" >&2; exit 2; }
if [[ "$R2_ENDPOINT" != https://* && "${LOCAL_TEST_ONLY:-}" != 1 ]]; then
  echo "restore: R2_ENDPOINT must be https://" >&2; exit 2
fi
if [[ "$target" =~ ://[^/@]*:[^/@]*@ ]]; then
  echo "restore: put the password in PGPASSWORD, not in the URL (it would show in ps)" >&2; exit 2
fi
# Ask the server, not the URL string: a URL can name the database in several ways (path, ?dbname=, PGDATABASE).
dbname=$(psql "$target" -XtAc 'select current_database()')
if [[ "$dbname" == "${POSTGRES_DB:-bkstudio}" && "$allow" != --allow-prod-name ]]; then
  echo "restore: refusing to restore into '$dbname' (the production DB name); add --allow-prod-name on a NEW server" >&2
  exit 2
fi
tables=$(psql "$target" -XtAc \
  "select count(*) from pg_tables where schemaname not in ('pg_catalog','information_schema')")
if ((tables != 0)); then
  echo "restore: target database is not empty ($tables tables); restore only into a fresh empty database" >&2; exit 2
fi

start=$SECONDS
work=$(mktemp -d /work/restore.XXXXXX)
trap 'gpgconf --homedir "$work/gnupg" --kill gpg-agent 2>/dev/null || true; rm -rf "$work"' EXIT
mkdir -m 700 "$work/gnupg"

printf 'user = "%s:%s"\n' "$R2_BACKUP_READ_ACCESS_KEY_ID" "$R2_BACKUP_READ_SECRET_ACCESS_KEY" \
  | curl -q --config - -fsS -m 600 --globoff --aws-sigv4 "aws:amz:${R2_REGION:-auto}:s3" \
    -o "$work/db.dump.gpg" "$R2_ENDPOINT/$R2_BACKUP_BUCKET/$key"
gpg --batch --quiet --homedir "$work/gnupg" --pinentry-mode loopback --no-symkey-cache --passphrase-fd 3 \
  --decrypt -o "$work/db.dump" "$work/db.dump.gpg" 3<<<"$BACKUP_ENCRYPTION_PASSPHRASE"
pg_restore --no-owner --single-transaction --exit-on-error -d "$target" "$work/db.dump"
echo "restore: OK, $key restored into $dbname in $((SECONDS - start)) s"
