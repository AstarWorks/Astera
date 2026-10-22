#!/usr/bin/env bash
# QA-1 (ADR-0186): boot the offline-mode `e2e` backend, run the protocol-bot suite inside
# the compose network, copy build/e2e-report.md out, stop the backend. Exit code = suite result.
#
#   scripts/e2e.sh            # fresh world every run
#   E2E_KEEP=1 scripts/e2e.sh # leave the backend running afterwards (faster re-runs, debugging)
#   E2E_REUSE=1 scripts/e2e.sh # do not wipe the e2e volume before starting
set -uo pipefail
cd "$(dirname "$0")/.."

C=(docker compose -p astera-e2e -f docker/docker-compose.e2e.yml --profile e2e)
REPORT=build/e2e-report.md
mkdir -p build

[ -n "${E2E_REUSE:-}" ] || "${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true

echo "[e2e] building + starting the backend (first run builds the Paper image: several minutes)"
"${C[@]}" up -d --build --wait e2e e2e-resource-pack || { echo "[e2e] backend failed to start"; "${C[@]}" logs --tail 80 e2e; exit 2; }

echo "[e2e] building the runner"
"${C[@]}" build e2e-runner || { echo "[e2e] runner build failed"; exit 2; }

docker rm -f astera-e2e-runner >/dev/null 2>&1 || true
"${C[@]}" run --name astera-e2e-runner --no-deps e2e-runner
rc=$?
docker cp astera-e2e-runner:/workspace/tools/e2e/build/e2e-report.md "$REPORT" 2>/dev/null \
  && echo "[e2e] report: $REPORT" || echo "[e2e] no report produced"
docker rm -f astera-e2e-runner >/dev/null 2>&1 || true

if [ -n "${E2E_KEEP:-}" ]; then
  echo "[e2e] backend left running (E2E_KEEP=1)"
else
  "${C[@]}" stop e2e e2e-resource-pack >/dev/null 2>&1 || true
fi
exit $rc
