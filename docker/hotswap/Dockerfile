# syntax=docker/dockerfile:1.7
#
# DEV-ONLY backend image with JetBrains Runtime 25 + the Astera hotswap agent
# (F3-9, ADR-0224).
#
# JBR carries the DCEVM-style "enhanced class redefinition"
# (-XX:+AllowEnhancedClassRedefinition): a redefinition may add/remove methods and
# fields, not only change method bodies. `docker-compose.hotswap.yml` builds this
# on top of the normal dev image and turns the flag and the agent on. The
# production image (docker/Dockerfile) is not touched and keeps the stock
# Temurin JDK.
#
#     ARG BASE        the normal backend image (astera/minecraft-server:dev)
#     ARG JBR_*       pinned JBR build; the checksum is the .checksum file next to the tarball

ARG BASE=astera/minecraft-server:dev

# ---------- Stage 1: the agent jar (a few hundred lines of plain Java, no dependencies) ----------
FROM gradle:9.5.1-jdk25 AS agent
WORKDIR /agent
COPY AsteraHotswapAgent.java ./
RUN javac --release 25 -d out AsteraHotswapAgent.java \
 && printf 'Premain-Class: AsteraHotswapAgent\nCan-Redefine-Classes: true\nCan-Retransform-Classes: false\n' > manifest.txt \
 && jar cfm hotswap-agent.jar manifest.txt -C out .

# ---------- Stage 2: the normal dev image with the JBR runtime ----------
FROM ${BASE}

ARG JBR_VERSION=25.0.4.1
ARG JBR_BUILD=b635.75
ARG JBR_SHA512_X64=6dfef0de1ad83cc93e94293e86a4d235c1890c10fdd8c9555a6d590e85a120bc9687e8b3283c9dd9861aec8398e6ec296e056812d187aa375364420be5fe6fa8
ARG TARGETARCH

RUN set -eu; \
    if [ "${TARGETARCH:-amd64}" != "amd64" ]; then echo "dev hotswap image: only linux/amd64 is pinned (got ${TARGETARCH})" >&2; exit 1; fi; \
    curl -fsSL -o /tmp/jbr.tar.gz "https://cache-redirector.jetbrains.com/intellij-jbr/jbr-${JBR_VERSION}-linux-x64-${JBR_BUILD}.tar.gz"; \
    echo "${JBR_SHA512_X64}  /tmp/jbr.tar.gz" | sha512sum -c -; \
    mkdir /opt/jbr; \
    tar -xzf /tmp/jbr.tar.gz -C /opt/jbr --strip-components=1; \
    rm /tmp/jbr.tar.gz; \
    /opt/jbr/bin/java -XX:+AllowEnhancedClassRedefinition -version

COPY --from=agent /agent/hotswap-agent.jar /opt/astera-dev/hotswap-agent.jar

# itzg's start script launches whatever `java` is first on PATH.
ENV JAVA_HOME=/opt/jbr \
    PATH=/opt/jbr/bin:${PATH} \
    ASTERA_DEV_HOTSWAP=1
