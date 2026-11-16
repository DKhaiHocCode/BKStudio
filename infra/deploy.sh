#!/usr/bin/env bash
# Deploy a release built by .github/workflows/deploy.yml (T-045, docs/DEPLOYMENT.md section 6b).
# Run ON the VPS, with .env in the repo root, after `git fetch --tags && git checkout <tag>`:
#   bash infra/deploy.sh vX.Y.Z      pull images, pre-deploy backup, up -d, wait for health; roll back by itself on failure
#   bash infra/deploy.sh --rollback  go back to the previous tag (no pull, no backup: it may be needed when R2 is down)
# State: infra/.deployed-tag (gitignored), line 1 = current tag, line 2 = previous tag. Written only after a healthy
# deploy. infra/.deploying marks a deploy that did not finish (killed SSH session, failed rollback); --rollback then
# goes back to line 1 instead of line 2. Never runs `down`, never touches volumes, never prints .env.
set -euo pipefail

cd "$(dirname "$0")/.."
state=infra/.deployed-tag
marker=infra/.deploying
tag_re='^v[0-9]+\.[0-9]+\.[0-9]+$'
compose=(docker compose --env-file .env -f infra/docker-compose.yml -f infra/docker-compose.ghcr.yml)
wait_seconds=180 # backend healthcheck start-period is 90 s; Caddy only starts once backend + frontend are healthy

die() { echo "deploy: $*" >&2; exit 1; }
# Compose waits for every service to be running and, where it has a healthcheck, healthy (backend: /api/health UP).
# Images missing locally are pulled by `up` itself (pull_policy missing).
up() { IMAGE_TAG="$1" "${compose[@]}" up -d --wait --wait-timeout "$wait_seconds"; }

(($# == 1)) || die "usage: $0 vX.Y.Z | --rollback"
[[ -f .env ]] || die ".env not found in $(pwd)"
command -v flock >/dev/null || die "flock not found (install util-linux)"
exec 9>infra/.deploy.lock
flock -n 9 || die "another deploy is running (lock infra/.deploy.lock)"

current="" previous=""
if [[ -f "$state" ]]; then
  { read -r current || true; read -r previous || true; } <"$state"
fi
for t in "$current" "$previous"; do
  [[ -z "$t" || "$t" =~ $tag_re ]] || die "corrupt state in $state; fix it by hand (line 1 = live tag, line 2 = previous)"
done

if [[ "$1" == --rollback ]]; then
  # An unfinished deploy left the site on an unknown tag: line 1 is the last tag known to be healthy.
  if [[ -f "$marker" ]]; then target="$current"; else target="$previous"; fi
  [[ -n "$target" ]] || die "no tag to roll back to in $state"
else
  target="$1"
  [[ "$target" =~ $tag_re ]] || die "usage: $0 vX.Y.Z | --rollback (got '$target')"
fi

echo "deploy: current='${current:-none}' target=$target"
if [[ "$1" != --rollback ]]; then
  IMAGE_TAG="$target" "${compose[@]}" pull
  for name in backend frontend backup; do
    img="ghcr.io/dkhaihoccode/bkstudio-$name:$target"
    # Compare with the digests in the GitHub Actions job summary.
    echo "deploy: $img $(docker image inspect --format '{{if .RepoDigests}}{{index .RepoDigests 0}}{{end}}' "$img")"
  done
  # Same command as docs/DEPLOYMENT.md section 4; uploads pre-deploy/<timestamp>.dump.gpg, aborts the deploy on error.
  IMAGE_TAG="$target" "${compose[@]}" run --rm backup /backup.sh pre-deploy
fi

echo "$target" >"$marker"
if ! up "$target"; then
  echo "deploy: $target failed or did not become healthy within ${wait_seconds}s" >&2
  [[ -n "$current" && "$current" != "$target" ]] || die "nothing to roll back to; inspect: docker compose ps / logs backend"
  echo "deploy: rolling back to $current" >&2
  up "$current" || die "ROLLBACK to $current FAILED too; site may be down. Fix, then: bash infra/deploy.sh --rollback"
  rm -f "$marker"
  die "rolled back to $current; $target is NOT deployed"
fi

# Re-deploying the current tag keeps the old previous tag, so --rollback still has somewhere to go.
[[ "$target" == "$current" ]] || previous="$current"
printf '%s\n%s\n' "$target" "$previous" >"$state.tmp"
mv "$state.tmp" "$state"
rm -f "$marker"
echo "deploy: OK, $target is live (previous: ${previous:-none})"
