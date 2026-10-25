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
COPY kernel /workspace/kernel/
COPY core /workspace/core/
# schema-gen / content-lint reference content/ from the root project.
COPY content /workspace/content/

# Build the 7 shaded plugin jars (ADR-0022). Tests are skipped here because CI
# runs `./gradlew check` separately. plugin:test-fixtures is NOT shipped.
RUN --mount=type=cache,id=astera-plugins-gradle,sharing=locked,target=/home/gradle/.gradle \
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
    VERSION=26.3 \
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

# G1: the e2e image (docker-compose.e2e.yml, E2E_FAST=1) runs the playthrough suites with shortened timers.
ARG E2E_FAST=0
COPY docker/e2e-tune-content.sh /tmp/e2e-tune-content.sh
RUN if [ "$E2E_FAST" = "1" ]; then sh /tmp/e2e-tune-content.sh /content; fi && rm /tmp/e2e-tune-content.sh

# Paper config patches (itzg PATCH_DEFINITIONS): the compose file points
# PATCH_DEFINITIONS=/patches at this directory on the lobby / game backends so
# Paper trusts Velocity's modern forwarding (secret from CFG_VELOCITY_SECRET).
COPY docker/paper-patches /patches/

# Server flavour (Folia migration, 2026-10-26). PAPER = itzg downloads Paper at
# start (TYPE/VERSION env). FOLIA = the Folia jar pinned by `folia` in
# gradle/libs.versions.toml (single source) is downloaded here, sha256-checked
# against the fill.papermc.io build record, and run as itzg TYPE=CUSTOM by the
# wrapper entrypoint. FOLIA also adds the threaded-regions patch
# (docker/folia-patches) to the itzg PATCH_DEFINITIONS dir.
#   docker build -f docker/Dockerfile --build-arg SERVER_TYPE=FOLIA -t astera/minecraft-server:folia .
ARG SERVER_TYPE=PAPER
ENV SERVER_TYPE=${SERVER_TYPE}
COPY gradle/libs.versions.toml /tmp/libs.versions.toml
COPY docker/folia-patches /tmp/folia-patches/
RUN set -eu; \
    if [ "$SERVER_TYPE" = "FOLIA" ]; then \
      v="$(sed -n 's/^folia = "\(.*\)".*/\1/p' /tmp/libs.versions.toml)"; \
      mc="${v%%.build.*}"; rest="${v#*.build.}"; build="${rest%%-*}"; \
      test -n "$mc" && test -n "$build"; \
      meta="$(curl -fsSL "https://fill.papermc.io/v3/projects/folia/versions/$mc/builds/$build")"; \
      url="$(printf '%s' "$meta" | jq -r '.downloads."server:default".url')"; \
      sha="$(printf '%s' "$meta" | jq -r '.downloads."server:default".checksums.sha256')"; \
      mkdir -p /opt/folia; \
      curl -fsSL -o /opt/folia/folia.jar "$url"; \
      echo "$sha  /opt/folia/folia.jar" | sha256sum -c -; \
      echo "$v" > /opt/folia/VERSION; \
      cp /tmp/folia-patches/*.json /patches/; \
    elif [ "$SERVER_TYPE" != "PAPER" ]; then \
      echo "unknown SERVER_TYPE=$SERVER_TYPE (PAPER|FOLIA)" >&2; exit 1; \
    fi; \
    rm -rf /tmp/libs.versions.toml /tmp/folia-patches
COPY --chmod=0755 docker/server-type-start.sh /usr/local/bin/astera-start
ENTRYPOINT ["/usr/local/bin/astera-start"]

# 25565 = Minecraft Java, 25575 = RCON. World/config volume is /data.
EXPOSE 25565 25575
VOLUME ["/data"]
