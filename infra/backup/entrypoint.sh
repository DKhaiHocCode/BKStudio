#!/usr/bin/env bash
# Scheduler (T-044): run backup.sh daily at 03:00 Asia/Singapore. A sleep loop instead of a cron daemon: one process,
# runs as non-root on a read-only rootfs. Container PID 1 is tini (compose `init: true`), so `docker stop` is instant.
set -euo pipefail

while true; do
  now=$(date +%s)
  next=$(TZ=Asia/Singapore date -d 'today 03:00' +%s)
  ((next > now)) || next=$(TZ=Asia/Singapore date -d 'tomorrow 03:00' +%s)
  echo "backup: next run at $(TZ=Asia/Singapore date -d "@$next" '+%F %T %Z')"
  sleep $((next - now))
  # A failure is already reported (log + /fail ping); keep the schedule running.
  /backup.sh daily || true
done
