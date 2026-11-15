# Backend image (T-042). Build from backend/:  docker build -t bkstudio-backend .
# Runtime is glibc Ubuntu, not Alpine/distroless: webp-imageio bundles a glibc-only libwebp (ADR/T-013 note).

# Temurin 25.0.5+8 JDK on Ubuntu 24.04 (noble). Digest = multi-arch index, pinned 2026-11-15.
FROM eclipse-temurin:25-jdk-noble@sha256:80f67fb3d6f76e2d817d98a4cc2a9cf05f054d4e6adc5e2144bde671d722e63f AS build
# mvnw needs unzip, otherwise it switches to the .tar.gz and the pinned distributionSha256Sum no longer matches.
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
# Tests run in CI (mvnw verify); the image build only packages. Spotless check still runs at process-sources.
RUN --mount=type=cache,target=/root/.m2 \
    sh ./mvnw --batch-mode --no-transfer-progress -DskipTests package \
    && cp target/bkstudio-backend-*.jar /src/app.jar

# Temurin 25.0.5+8 JRE on Ubuntu 24.04 (noble).
FROM eclipse-temurin:25-jre-noble@sha256:e3b6f7887a50f20d8f07abf279d8a81a5953fb398a9555bd9edd56b9bd15968c
RUN useradd --system --uid 10001 --user-group --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /src/app.jar /app/app.jar
USER 10001:10001
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
# The JRE image has no curl/wget: bash /dev/tcp avoids starting a second JVM every interval.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /api/health HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]
# java.io.tmpdir must stay writable and exec (libwebp is extracted and loaded from it); compose mounts a small tmpfs
# at /tmp and puts multipart uploads on a separate noexec tmpfs (SPRING_SERVLET_MULTIPART_LOCATION=/uploads).
# Extra JVM flags: set JAVA_TOOL_OPTIONS. Extra app args (e.g. --create-admin=<email>) are appended by `run`.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-XX:MaxRAMPercentage=50", "-XX:+ExitOnOutOfMemoryError", "-Djava.io.tmpdir=/tmp", "-jar", "/app/app.jar"]
