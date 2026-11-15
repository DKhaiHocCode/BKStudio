# Backup / restore tools (T-044). Same base image and digest as the postgres service in infra/docker-compose.yml, so
# pg_dump/pg_restore always match the server major version (16). The image already ships gpg and tzdata; curl does the
# S3 (SigV4) upload/download, so no AWS CLI is needed.
FROM postgres:16@sha256:63e83b5a3dc36a917dccb76fe1bd2ac51f1ec9c1deb47ff20d4f04233bf6eae9

# Debian drops old package versions from its mirrors, so an exact pin would break rebuilds; the base digest pins the rest.
# hadolint ignore=DL3008
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --chmod=0755 entrypoint.sh backup.sh restore.sh /

USER postgres
# Drop the base image's docker-entrypoint.sh (server init). With no ENTRYPOINT,
# `docker compose run --rm backup /backup.sh pre-deploy` simply replaces the scheduler command.
ENTRYPOINT []
CMD ["/entrypoint.sh"]
