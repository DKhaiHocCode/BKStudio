#!/usr/bin/env bash
# Usage: backup.sh [label]   label = daily (default, used by the scheduler) | pre-deploy | other [a-z0-9-]+
# pg_dump -Fc -> gpg AES256 (symmetric) -> PUT to the backup bucket, key scoped to that bucket only (T-044,
# docs/DEPLOYMENT.md §4; R2 has no write-only token, a bucket lock blocks delete/overwrite).
# Secrets come from env only and never reach argv, ps or logs: gpg reads the passphrase from fd 3, curl reads the key
# pair and the healthcheck URL from a config on stdin. The dump lives on the /work tmpfs and is removed on exit.
set -euo pipefail

label="${1:-daily}"
[[ "$label" =~ ^[a-z0-9-]+$ ]] || { echo "backup: invalid label" >&2; exit 2; }

# Healthchecks.io-style ping: "" = success, "/fail" = failure. A missing ping alerts too (dead container, no run).
# https only, except local-test (plain http listener on the compose network).
proto=(--proto "=https")
[[ "${LOCAL_TEST_ONLY:-}" != 1 ]] || proto=()
ping_hc() {
  [[ -n "${BACKUP_HEALTHCHECK_URL:-}" ]] || return 0
  printf 'url = "%s%s"\n' "${BACKUP_HEALTHCHECK_URL%/}" "$1" \
    | curl -q --config - "${proto[@]}" -fs -m 10 --retry 3 -o /dev/null || echo "backup: healthcheck ping failed" >&2
}

work=""
finish() {
  local rc=$?
  [[ -z "$work" ]] || { gpgconf --homedir "$work/gnupg" --kill gpg-agent 2>/dev/null || true; rm -rf "$work"; }
  if ((rc == 0)); then ping_hc ""; else echo "backup: FAILED (exit $rc)" >&2; ping_hc /fail; fi
  exit "$rc"
}
# A stopped run must ping /fail, not success: turn the signal into a non-zero exit, then finish runs.
trap 'exit 143' TERM INT
trap finish EXIT

[[ -n "${BACKUP_HEALTHCHECK_URL:-}" ]] \
  || echo "backup: WARNING: BACKUP_HEALTHCHECK_URL is not set, a failed or missing backup will alert nobody" >&2
: "${POSTGRES_DB:?}" "${POSTGRES_USER:?}" "${PGPASSWORD:?}" "${R2_ENDPOINT:?}" "${R2_BACKUP_BUCKET:?}"
: "${R2_BACKUP_ACCESS_KEY_ID:?}" "${R2_BACKUP_SECRET_ACCESS_KEY:?}" "${BACKUP_ENCRYPTION_PASSPHRASE:?}"
if [[ "$R2_ENDPOINT" != https://* && "${LOCAL_TEST_ONLY:-}" != 1 ]]; then
  echo "backup: R2_ENDPOINT must be https://" >&2; exit 2
fi

s3_put() { # <file> <object key>
  printf 'user = "%s:%s"\n' "$R2_BACKUP_ACCESS_KEY_ID" "$R2_BACKUP_SECRET_ACCESS_KEY" \
    | curl -q --config - -fsS -m 600 --retry 3 --aws-sigv4 "aws:amz:${R2_REGION:-auto}:s3" \
      -T "$1" -o /dev/null "$R2_ENDPOINT/$R2_BACKUP_BUCKET/$2"
  echo "backup: uploaded $2 ($(stat -c %s "$1") bytes)"
}

rm -rf /work/backup.* # leftovers of a run killed before its trap could clean up
work=$(mktemp -d /work/backup.XXXXXX)
mkdir -m 700 "$work/gnupg"
ts=$(date -u +%Y-%m-%dT%H%M%SZ)

pg_dump -h "${PGHOST:-postgres}" -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc -f "$work/db.dump"
pg_restore --list "$work/db.dump" >/dev/null # the archive is readable, not truncated
gpg --batch --quiet --homedir "$work/gnupg" --pinentry-mode loopback --no-symkey-cache --passphrase-fd 3 \
  --symmetric --cipher-algo AES256 -o "$work/db.dump.gpg" "$work/db.dump" 3<<<"$BACKUP_ENCRYPTION_PASSPHRASE"
rm -f "$work/db.dump"

s3_put "$work/db.dump.gpg" "$label/$ts.dump.gpg"
# Weekly copy on Sunday (Singapore time, when the 03:00 run happens); a second PUT, since CopyObject needs read access.
if [[ "$label" == daily && "$(TZ=Asia/Singapore date +%u)" == 7 ]]; then
  s3_put "$work/db.dump.gpg" "weekly/$ts.dump.gpg"
fi
echo "backup: OK"
