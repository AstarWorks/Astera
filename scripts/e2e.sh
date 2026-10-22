#!/usr/bin/env bash
# QA-1 (ADR-0222) + QA-3: boot the offline-mode `e2e` backend, run the protocol-bot suite inside
# the compose network, copy build/e2e-report.md out, stop the backend. Exit code = suite result.
#
#   scripts/e2e.sh              # fresh world every run
#   scripts/e2e.sh down         # stop + remove THIS checkout's e2e project (containers, volumes, images)
#   E2E_KEEP=1 scripts/e2e.sh   # leave the backend running afterwards (faster re-runs, debugging)
#   E2E_REUSE=1 scripts/e2e.sh  # do not wipe the e2e volume before starting
#   E2E_ID=mytag scripts/e2e.sh # override the per-checkout id (default: sha256 of the checkout path)
#
# QA-3 isolation: every checkout gets its own id ASTERA_E2E_ID (8 hex chars). It names the compose
# project (astera-e2e-<id>), the image tags (:e2e-<id>), the `astera.e2e.id` image label, the runner
# container and the volume (via the project). Host ports are ephemeral, so runs from different
# worktrees never collide.
set -uo pipefail
cd "$(dirname "$0")/.."

ROOT="$(pwd -P)"
if [ -n "${E2E_ID:-}" ]; then
  ASTERA_E2E_ID="$E2E_ID"
else
  ASTERA_E2E_ID="$(printf '%s' "$ROOT" | { sha256sum 2>/dev/null || shasum -a 256; } | cut -c1-8)"
fi
export ASTERA_E2E_ID
PROJECT="astera-e2e-$ASTERA_E2E_ID"
RUNNER="$PROJECT-runner"
REPORT=build/e2e-report.md

C=(docker compose -p "$PROJECT" -f docker/docker-compose.e2e.yml --profile e2e)

# Removes everything this checkout's id left behind: the compose project (containers, network,
# named volume, tagged images), then dangling images and volumes still carrying this id's labels.
cleanup_id() {
  docker rm -f "$RUNNER" >/dev/null 2>&1 || true
  "${C[@]}" down -v --rmi all --remove-orphans >/dev/null 2>&1 || true
  docker image ls -q --filter "label=astera.e2e.id=$ASTERA_E2E_ID" | sort -u \
    | xargs -r docker rmi -f >/dev/null 2>&1 || true
  docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT" \
    | xargs -r docker volume rm >/dev/null 2>&1 || true
}

if [ "${1:-}" = "down" ]; then
  echo "[e2e] removing project $PROJECT (checkout id $ASTERA_E2E_ID)"
  cleanup_id
  exit 0
fi

mkdir -p build
echo "[e2e] checkout id $ASTERA_E2E_ID (project $PROJECT)"

[ -n "${E2E_REUSE:-}" ] || "${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true

echo "[e2e] building + starting the backend (first run builds the Paper image: several minutes)"
"${C[@]}" up -d --build --wait e2e e2e-resource-pack || { echo "[e2e] backend failed to start"; "${C[@]}" logs --tail 80 e2e; exit 2; }

echo "[e2e] building the runner"
"${C[@]}" build e2e-runner || { echo "[e2e] runner build failed"; exit 2; }

docker rm -f "$RUNNER" >/dev/null 2>&1 || true
"${C[@]}" run --name "$RUNNER" --no-deps e2e-runner
rc=$?
docker cp "$RUNNER:/workspace/tools/e2e/build/e2e-report.md" "$REPORT" 2>/dev/null \
  && echo "[e2e] report: $REPORT" || echo "[e2e] no report produced"
docker rm -f "$RUNNER" >/dev/null 2>&1 || true

if [ -n "${E2E_KEEP:-}" ]; then
  echo "[e2e] backend left running (E2E_KEEP=1; tear down with: make e2e-down)"
else
  "${C[@]}" stop e2e e2e-resource-pack >/dev/null 2>&1 || true
fi
exit $rc
