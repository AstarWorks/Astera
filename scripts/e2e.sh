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
#
# QA-7: bots join THROUGH Velocity (offline-mode, modern forwarding, -Dvelocity.packet-decode-logging=true),
# the production path, and the run fails if Velocity's log shows any exception / decode error.
#   E2E_VIA=direct scripts/e2e.sh   # bots straight to the backend, no Velocity (diagnostics)
# Velocity's log is copied to build/e2e-velocity.log (uploaded by CI as the e2e-velocity-log artifact).
#
# OPS-10 resilience:
#   - leak cleanup: this checkout's project is removed at start and (trap) on every exit incl. SIGINT/SIGTERM;
#     stale OTHER astera-e2e-* projects are reaped when older than E2E_STALE_HOURS (default 3) AND no live run
#     owns them (stamp file with pid+host in $E2E_STAMP_DIR). astera-dev-* is never touched.
#     E2E_REAP=0 disables the reaper.
#   - load pre-flight: wait while load1 > E2E_LOAD_FACTOR (default 2) x nproc, up to E2E_LOAD_WAIT_SECS (default
#     900); then boot anyway with a warning. Load at start/end is appended to build/e2e-report.md.
set -uo pipefail
cd "$(dirname "$0")/.."

E2E_VIA="${E2E_VIA:-velocity}"
export E2E_VIA
if [ "$E2E_VIA" = "velocity" ]; then export E2E_PATCHES=/patches; else export E2E_PATCHES=""; fi
SERVICES=(e2e e2e-resource-pack)
[ "$E2E_VIA" = "velocity" ] && SERVICES+=(e2e-velocity)

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

# ---- OPS-10: stamps, reaper, load ---------------------------------------------------------------------
STAMP_DIR="${E2E_STAMP_DIR:-/tmp/claude-$(id -u)/astera-e2e-runs}"
STALE_HOURS="${E2E_STALE_HOURS:-3}"
HOST="$(hostname 2>/dev/null || echo unknown)"
STAMP="$STAMP_DIR/$PROJECT"

stamp_live() { # $1 = project; true when a live run on this host owns it
  local f="$STAMP_DIR/$1" pid host
  [ -f "$f" ] || return 1
  read -r pid host _ < "$f" || return 1
  [ "$host" = "$HOST" ] && [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

# Removes one stale project by name. Only ever called with names matching ^astera-e2e-[A-Za-z0-9]+$.
reap_project() {
  local p="$1" id="${1#astera-e2e-}"
  docker ps -aq --filter "label=com.docker.compose.project=$p" | xargs -r docker rm -f -v >/dev/null 2>&1 || true
  docker volume ls -q --filter "label=com.docker.compose.project=$p" | xargs -r docker volume rm -f >/dev/null 2>&1 || true
  docker network ls -q --filter "label=com.docker.compose.project=$p" | xargs -r docker network rm >/dev/null 2>&1 || true
  docker image ls -q --filter "label=astera.e2e.id=$id" | sort -u | xargs -r docker rmi -f >/dev/null 2>&1 || true
  rm -f "$STAMP_DIR/$p"
}

# Age (hours, integer) of the oldest container/network of a project; empty when unknown.
project_age_hours() {
  local created oldest="" now t line
  now="$(date +%s)"
  created="$( { docker ps -a --filter "label=com.docker.compose.project=$1" --format '{{.CreatedAt}}'
                docker network ls --filter "label=com.docker.compose.project=$1" --format '{{.CreatedAt}}'; } 2>/dev/null)"
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    t="$(date -d "${line% [A-Z]*}" +%s 2>/dev/null)" || continue   # "2026-10-26 01:02:03 +0000 UTC"
    if [ -z "$oldest" ] || [ "$t" -lt "$oldest" ]; then oldest="$t"; fi
  done <<< "$created"
  if [ -n "$oldest" ]; then echo $(( (now - oldest) / 3600 )); fi
}

reap_stale() {
  [ "${E2E_REAP:-1}" = "0" ] && return 0
  local p age n=0
  for p in $( { docker ps -a --format '{{.Label "com.docker.compose.project"}}'
                docker network ls --format '{{.Label "com.docker.compose.project"}}'; } 2>/dev/null | sort -u); do
    [[ "$p" =~ ^astera-e2e-[A-Za-z0-9]+$ ]] || continue   # never astera-dev-*, never anything else
    [ "$p" = "$PROJECT" ] && continue
    stamp_live "$p" && continue
    age="$(project_age_hours "$p")"
    # Unknown age => keep (cannot prove it is stale). The age gate protects runs started without a stamp.
    if [ -z "$age" ] || [ "$age" -lt "$STALE_HOURS" ]; then continue; fi
    echo "[e2e] reaping stale project $p (age ${age}h >= ${STALE_HOURS}h, no live owner)"
    reap_project "$p"; n=$((n+1))
  done
  if [ "$n" -gt 0 ]; then docker network prune -f >/dev/null 2>&1 || true; fi
}

load1() { cut -d' ' -f1 /proc/loadavg 2>/dev/null || echo 0; }
load_triple() { cut -d' ' -f1-3 /proc/loadavg 2>/dev/null || echo "n/a"; }
NPROC="$(nproc 2>/dev/null || echo 1)"
LOAD_WAIT_TIMED_OUT=0

wait_for_load() {
  local factor="${E2E_LOAD_FACTOR:-2}" max="${E2E_LOAD_WAIT_SECS:-900}" waited=0 l
  while :; do
    l="$(load1)"
    awk -v l="$l" -v n="$NPROC" -v f="$factor" 'BEGIN{exit !(l > f*n)}' || return 0
    if [ "$waited" -ge "$max" ]; then
      echo "[e2e] WARNING: load1 $l still > ${factor}x$NPROC cores after ${max}s; booting anyway (timing results unreliable)"
      LOAD_WAIT_TIMED_OUT=1
      return 0
    fi
    [ "$waited" -eq 0 ] && echo "[e2e] host overloaded (load1 $l > ${factor}x$NPROC cores); waiting up to ${max}s"
    sleep 15; waited=$((waited + 15))
  done
}

if [ "${1:-}" = "down" ]; then
  echo "[e2e] removing project $PROJECT (checkout id $ASTERA_E2E_ID)"
  cleanup_id
  exit 0
fi

mkdir -p build "$STAMP_DIR"
rm -f "$REPORT"   # never append load data to a previous run's report
printf '%s %s %s\n' "$$" "$HOST" "$(date +%s)" > "$STAMP"

# Exit trap: always drop the runner and (unless E2E_KEEP) this project's containers, network and volume.
# Images stay cached for the next run. The stamp is always removed. Preserves the exit code.
on_exit() {
  local rc=$?
  trap - EXIT INT TERM
  docker rm -f "$RUNNER" >/dev/null 2>&1 || true
  if [ -z "${E2E_KEEP:-}" ]; then
    if [ -n "${E2E_REUSE:-}" ]; then "${C[@]}" down --remove-orphans >/dev/null 2>&1 || true
    else "${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true; fi
  fi
  rm -f "$STAMP"
  exit "$rc"
}
trap on_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

reap_stale
wait_for_load
LOAD_START="$(load_triple)"; T_START="$(date -u +%FT%TZ)"
echo "[e2e] load avg at start: $LOAD_START ($NPROC cores)"
echo "[e2e] checkout id $ASTERA_E2E_ID (project $PROJECT)"

[ -n "${E2E_REUSE:-}" ] || "${C[@]}" down -v --remove-orphans >/dev/null 2>&1 || true

echo "[e2e] building + starting the backend (first run builds the Paper image: several minutes)"
"${C[@]}" up -d --build --wait "${SERVICES[@]}" || { echo "[e2e] backend failed to start"; "${C[@]}" logs --tail 80 "${SERVICES[@]}"; exit 2; }

echo "[e2e] building the runner"
"${C[@]}" build e2e-runner || { echo "[e2e] runner build failed"; exit 2; }

docker rm -f "$RUNNER" >/dev/null 2>&1 || true
"${C[@]}" run --name "$RUNNER" --no-deps e2e-runner
rc=$?
docker cp "$RUNNER:/workspace/tools/e2e/build/e2e-report.md" "$REPORT" 2>/dev/null \
  && echo "[e2e] report: $REPORT" || echo "[e2e] no report produced"
docker rm -f "$RUNNER" >/dev/null 2>&1 || true

# OPS-10: record host load so readers can discard runs made under overload.
LOAD_END="$(load_triple)"
[ -f "$REPORT" ] || printf '# E2E report\n\n(no suite report produced)\n' > "$REPORT"
{
  printf '\n## Host load\n\n'
  printf -- '- cores: %s\n- load avg (1/5/15m) at start: %s (%s)\n- load avg (1/5/15m) at end: %s (%s)\n' \
    "$NPROC" "$LOAD_START" "$T_START" "$LOAD_END" "$(date -u +%FT%TZ)"
  if [ "$LOAD_WAIT_TIMED_OUT" = 1 ]; then printf -- '- **OVERLOADED at start** (load1 stayed above %sx cores for the whole wait): discard timing results\n' "${E2E_LOAD_FACTOR:-2}"; fi
  if awk -v l="${LOAD_END%% *}" -v n="$NPROC" 'BEGIN{exit !(l > 2*n)}'; then printf -- '- **OVERLOADED at end** (load1 > 2x cores): timing results may be unreliable\n'; fi
} >> "$REPORT"

# QA-7: Velocity's own verdict. Any exception / decode failure in the proxy log fails the run, even when
# every bot check passed (the bot that hit it may already have been written off as a flaky disconnect).
if [ "$E2E_VIA" = "velocity" ]; then
  VLOG=build/e2e-velocity.log
  "${C[@]}" logs --no-color --no-log-prefix e2e-velocity > "$VLOG" 2>&1 || true
  # A client socket that vanished mid-write (backend kicked the bot) logs "decode frame" + "Broken pipe": a peer
  # disconnect race, not a protocol decode failure. Those line pairs are ignored.
  bad="$(awk '{l[NR]=$0} END{for(i=1;i<=NR;i++){ if (l[i] ~ /Exception|did not decode|Failed to decode|decode.*(error|fail)|ERROR/ && l[i] !~ /Broken pipe|Connection reset/ && l[i+1] !~ /Broken pipe|Connection reset/) print i":"l[i] }}' "$VLOG" | head -20)"
  if [ -n "$bad" ]; then
    echo "[e2e] FAIL: Velocity logged decode errors / exceptions (see $VLOG):"; echo "$bad"
    rc=1
  else
    echo "[e2e] Velocity log clean: $(grep -c 'has connected' "$VLOG") joins, no exceptions / decode errors"
  fi
fi

if [ -n "${E2E_KEEP:-}" ]; then
  echo "[e2e] backend left running (E2E_KEEP=1; tear down with: make e2e-down)"
fi
exit $rc   # the on_exit trap tears the project down (unless E2E_KEEP)
