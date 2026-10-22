# syntax=docker/dockerfile:1.7
#
# Astera Minecraft server image.
#
# Two stages:
#   1. `builder`  — Gradle + JDK 25, produces the 7 shaded plugin jars.
#   2. `runtime`  — itzg/minecraft-server:java25 with plugin jars + content baked in.
#
# Build context is the *repository root* (one level up from this Dockerfile),
# so the plugin sources can be copied into the builder stage. Example:
#
#     docker build -f docker/Dockerfile -t astera/minecraft-server:dev .
#
# For local development we usually drive this through docker-compose, which
# already passes the correct build context (see docker-compose.yml).

# ---------- Stage 1: build the shaded Paper plugin jar ----------
FROM gradle:9.5.1-jdk25 AS builder

WORKDIR /workspace

# Copy the bare minimum needed for dependency resolution first, so that the
# Gradle dependency layer can be cached between builds.
COPY settings.gradle.kts build.gradle.kts gradle.properties /workspace/
COPY gradle /workspace/gradle/
COPY buildSrc /workspace/buildSrc/
COPY config /workspace/config/
COPY tools /workspace/tools/

# Now copy plugin sources. Anything not relevant (build/, .gradle/, IDE files)
# is excluded via Dockerfile.dockerignore.
COPY plugin /workspace/plugin/
# schema-gen / content-lint reference content/ from the root project.
COPY content /workspace/content/

# Build the 7 shaded plugin jars (ADR-0022). Tests are skipped here because CI
# runs `./gradlew check` separately. plugin:test-fixtures is NOT shipped.
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle --no-daemon -x test \
        :plugin:core:shadowJar \
        :plugin:items:shadowJar \
        :plugin:combat:shadowJar \
        :plugin:match:shadowJar \
        :plugin:stats:shadowJar \
        :plugin:economy:shadowJar \
        :plugin:persistence:shadowJar \
 && mkdir /out \
 && cp plugin/*/build/libs/astera-*.jar /out/ \
 && test "$(ls /out | wc -l)" -eq 7

# ---------- Stage 2: itzg/minecraft-server runtime ----------
FROM itzg/minecraft-server:java25 AS runtime

# itzg/minecraft-server env defaults — see
# https://github.com/itzg/docker-minecraft-server for the full reference.
# EULA is intentionally left FALSE; the operator must opt in via env or the
# docker-compose file. This keeps the base image redistribution-safe.
ENV EULA=FALSE \
    TYPE=PAPER \
    VERSION=26.1.2 \
    MEMORY=4G \
    USE_AIKAR_FLAGS=true \
    MAX_PLAYERS=20 \
    ONLINE_MODE=FALSE \
    MOTD="Astera dev server" \
    ENABLE_RCON=true \
    RCON_PORT=25575

# Plugin jars: itzg copies /plugins -> /data/plugins on every start.
COPY --from=builder /out/ /plugins/

# Content: the core plugin checks `<server root>/../content` (= /content) before
# `<server root>/content`. The Docker daemon is shared with the host, so a bind
# mount of ../content would appear empty; bake it into the image at /content.
# It lives outside the /data volume, so each rebuilt image carries exactly the
# repo's content — deleted files do not linger.
COPY content /content/

# 25565 = Minecraft Java, 25575 = RCON. World/config volume is /data.
EXPOSE 25565 25575
VOLUME ["/data"]
