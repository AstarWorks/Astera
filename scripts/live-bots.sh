#!/usr/bin/env bash
# QA-2 (ADR-0221): run protocol-bot scenarios against the LIVE docker stack (compose project astera-dev).
#
#   scripts/live-bots.sh [smoke|weapons|match|chat|all]     # or: make live-bots SCENARIO=chat
#
# The bots log in DIRECTLY to the lobby/game backends from a throw-away runner container on the
# stack's own network (astera-dev_default), answering Paper's Velocity modern-forwarding query with
# the shared secret - so no Mojang account is needed, Velocity is untouched and no port is exposed.
# The secret is read from the running lobby backend each time and handed to the runner as an
# environment variable only (never written to disk or printed). The stack is not restarted.
# Report: build/live-report.md. Exit code = scenario result.
set -uo pipefail
cd "$(dirname "$0")/.."

SCENARIO="${1:-${SCENARIO:-all}}"
case "$SCENARIO" in smoke|weapons|match|chat|all) ;; *) echo "[live-bots] unknown scenario '$SCENARIO' (smoke|weapons|match|chat|all)"; exit 2;; esac

PROJECT="${LIVE_COMPOSE_PROJECT:-astera-dev}"
NETWORK="${PROJECT}_default"
REPORT=build/live-report.md
mkdir -p build

cid() { docker ps -q --filter "label=com.docker.compose.project=$PROJECT" --filter "label=com.docker.compose.service=$1" | head -n1; }
LOBBY=$(cid lobby); GAME=$(cid game)
if [ -z "$LOBBY" ] || [ -z "$GAME" ]; then
  echo "[live-bots] the '$PROJECT' stack (lobby + game) is not running; start it with 'make docker-up'"; exit 2
fi

# Paper's own copy of the forwarding secret (docker/.env itself is not readable here and need not be).
SECRET=$(docker exec "$LOBBY" sh -c "grep -A6 '^  velocity:' /data/config/paper-global.yml | grep 'secret:'" | head -n1 | sed 's/.*secret:[[:space:]]*//; s/^["'\'']//; s/["'\'']$//')
if [ -z "$SECRET" ]; then echo "[live-bots] could not read the Velocity forwarding secret from the lobby backend"; exit 2; fi
RCON_PASSWORD=$(docker exec "$LOBBY" printenv RCON_PASSWORD 2>/dev/null || echo astera)

echo "[live-bots] building the runner image (cached after the first run)"
docker build -q -f docker/Dockerfile.e2e-runner -t astera/e2e-runner:live . >/dev/null || { echo "[live-bots] runner build failed"; exit 2; }

# The content the RUNNING backend serves (items/skills drive which weapons the bots test), not whatever the
# checkout holds: copied out of the lobby container and into the runner (no bind mounts: the daemon may be remote).
LIVE_CONTENT=build/live-content
rm -rf "$LIVE_CONTENT" && mkdir -p "$LIVE_CONTENT"
docker cp "$LOBBY:/content/." "$LIVE_CONTENT" >/dev/null 2>&1 || { echo "[live-bots] could not copy /content from the lobby backend"; exit 2; }

NAME=astera-live-bots-runner
docker rm -f "$NAME" >/dev/null 2>&1 || true
echo "[live-bots] scenario=$SCENARIO network=$NETWORK"
docker create --name "$NAME" --network "$NETWORK" \
  -e VELOCITY_FORWARDING_SECRET="$SECRET" -e GRADLE_OPTS="-Dorg.gradle.daemon=false" \
  -v "${PROJECT}_lobby-data:/live/lobby:ro" -v "${PROJECT}_game-data:/live/game:ro" \
  astera/e2e-runner:live \
  gradle --no-daemon --offline --console=plain :tools:e2e:live \
    -Plive.scenario="$SCENARIO" -Plive.rconPassword="$RCON_PASSWORD" -Plive.contentDir=/live-content ${LIVE_WEAPONS:+-Plive.weapons=$LIVE_WEAPONS} >/dev/null || exit 2
docker cp "$LIVE_CONTENT" "$NAME:/live-content" >/dev/null || exit 2
docker start -a "$NAME"
rc=$?
docker cp "$NAME:/workspace/tools/e2e/build/live-report.md" "$REPORT" 2>/dev/null \
  && echo "[live-bots] report: $REPORT" || echo "[live-bots] no report produced"
mkdir -p build/live-test-results
docker cp "$NAME:/workspace/tools/e2e/build/test-results/live/." build/live-test-results 2>/dev/null || true
docker rm -f "$NAME" >/dev/null 2>&1 || true
exit $rc
