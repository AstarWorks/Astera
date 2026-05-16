# syntax=docker/dockerfile:1.7
#
# Astera Minecraft server image.
#
# Two stages:
#   1. `builder`  — Gradle + JDK 25, produces the shaded plugin jar.
#   2. `runtime`  — itzg/minecraft-server:java25 with the plugin jar baked in.
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
COPY tools /workspace/tools/

# Now copy plugin sources. Anything not relevant (build/, .gradle/, IDE files)
# is excluded via .dockerignore.
COPY plugin /workspace/plugin/

# Build the shaded jar. We skip tests here because CI runs `./gradlew check`
# separately; the Dockerfile's job is just to produce the runtime artifact.
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle --no-daemon :plugin:platform-paper-plugin:shadowJar -x test

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

# Drop the shaded plugin jar into the plugins directory. itzg's entrypoint
# will copy /plugins -> /data/plugins on first boot.
COPY --from=builder \
    /workspace/plugin/platform-paper-plugin/build/libs/astera-paper-*.jar \
    /plugins/astera.jar

# 25565 = Minecraft Java, 25575 = RCON. World/config volume is /data.
EXPOSE 25565 25575
VOLUME ["/data"]
