#!/usr/bin/env bash
# Runs ON astera-mc (host tools only: bash, docker, git, python3, flock, rsync). Called by tools/remote/run.sh over ssh:
#   remote-exec.sh prepare <id>
#   remote-exec.sh run <id> <vfx|gradle> <envfile> -- <command...>
#   remote-exec.sh status
# Layout under $HB (= ASTERA_HEAVY_BASE, default ~/astera-heavy):
#   w/<id>/repo      synced working copy of one local checkout      locks/slot.<n>.{lock,owner}
#   home/            runner $HOME (gradle + MC client caches)       locks/claude-1000  runner /tmp/claude-1000
#   heavy.env        host defaults (HEAVY_SLOTS=...)                bin/remote-exec.sh copy for `status`
set -uo pipefail

HB="${ASTERA_HEAVY_BASE:-astera-heavy}"
case "$HB" in /*) ;; *) HB="$HOME/$HB" ;; esac
IMAGE="${HEAVY_IMAGE:-astera/heavy-runner:local}"

log() { printf '[heavy] %s\n' "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

# heavy.env supplies defaults only for variables the caller did not set.
load_heavy_env() {
  local k v
  [ -f "$HB/heavy.env" ] || return 0
  while IFS='=' read -r k v; do
    case "$k" in ''|'#'*) continue ;; esac
    [[ "$k" =~ ^[A-Z_][A-Z0-9_]*$ ]] || continue
    [ -n "${!k+x}" ] || export "$k=$v"
  done < "$HB/heavy.env"
}

mem_available_gb() { awk '/^MemAvailable:/ {printf "%d", $2 / 1048576}' /proc/meminfo; }

# ---------------------------------------------------------------- prepare
cmd_prepare() {
  local id="${1:?id}" w r
  w="$HB/w/$id"; r="$w/repo"
  mkdir -p "$r" && cd "$r" || die "cannot create $r"
  [ -d .git ] || git init -q
  git config receive.denyCurrentBranch ignore
  git rev-parse -q --verify refs/heads/heavy-sync >/dev/null || die "no heavy-sync ref in $r (the push failed?)"
  # index = the local HEAD commit, working tree = whatever rsync just wrote (so `git status` shows the local uncommitted state)
  git symbolic-ref HEAD refs/heads/heavy-sync && git reset -q --mixed heavy-sync || die "git reset failed in $r"
  # files deleted locally: untracked, non-ignored remote files that the local checkout no longer lists
  if [ -f "$w/files.list0" ]; then
    python3 -I - "$w/files.list0" <<'PY'
import os, subprocess, sys
keep = set(open(sys.argv[1], 'rb').read().split(b'\0'))
out = subprocess.run(['git', 'ls-files', '-z', '-o', '--exclude-standard'], capture_output=True, check=True).stdout
n = 0
for p in out.split(b'\0'):
    if p and p not in keep:
        try:
            os.remove(p); n += 1
        except OSError:
            pass
print('[heavy] removed %d stale file(s)' % n, file=sys.stderr)
PY
  fi
}

# ---------------------------------------------------------------- run
SLOT_N=""; SLOT_FD=""; NAME=""; TIMER=""; WATCH=""; MERGED=""; ABNORMAL=0; RC=1; KIND=""; REPO=""

owner_files() { local f; for f in "$HB"/locks/slot.*.owner; do [ -f "$f" ] && printf '[%s] %s ' "$(basename "$f" .owner)" "$(cat "$f")"; done; }

acquire_slot() {
  local slots="${HEAVY_SLOTS:-1}" timeout="${HEAVY_QUEUE_TIMEOUT:-3600}" start=$SECONDS last=0 announced=0 i f fd
  mkdir -p "$HB/locks"
  while :; do
    for i in $(seq 1 "$slots"); do
      f="$HB/locks/slot.$i.lock"
      exec {fd}>"$f" || die "cannot open $f"
      if flock -n "$fd"; then SLOT_N=$i; SLOT_FD=$fd; return 0; fi
      exec {fd}>&-
    done
    if [ "$announced" = 0 ]; then
      log "queued: all $slots slot(s) busy; held by $(owner_files)"
      announced=1; last=$SECONDS
    elif [ $((SECONDS - last)) -ge 60 ]; then
      log "still queued after $((SECONDS - start))s (limit ${timeout}s): $(owner_files)"
      last=$SECONDS
    fi
    [ $((SECONDS - start)) -lt "$timeout" ] || die "queue timeout after ${timeout}s (HEAVY_QUEUE_TIMEOUT)"
    sleep 5
  done
}

memory_guard() {
  local need="$1" wait="${HEAVY_MEM_WAIT:-600}" start=$SECONDS announced=0 have
  while :; do
    have="$(mem_available_gb)"
    [ "$have" -ge "$need" ] && return 0
    [ "$announced" = 1 ] || { log "memory guard: host has ${have} GB available < ${need} GB (HEAVY_MIN_FREE_GB; the astera-dev-* containers keep ~9 GB), waiting"; announced=1; }
    [ $((SECONDS - start)) -lt "$wait" ] || die "memory guard: still ${have} GB available after ${wait}s (HEAVY_MEM_WAIT)"
    sleep 5
  done
}

# base docker run arguments (BASE_ARGS) for a runner container called $1
base_args() {
  local name="$1" sockgid
  sockgid="$(stat -c %g /var/run/docker.sock)"
  BASE_ARGS=(--rm --init --name "$name" --hostname "$name" --label astera.heavy=1
    --user "$(id -u):$(id -g)" --group-add "$sockgid"
    -v /var/run/docker.sock:/var/run/docker.sock
    -v "$REPO:$REPO" -w "$REPO"
    -v "$HB/home:/heavy-home" -e HOME=/heavy-home -e GRADLE_USER_HOME=/heavy-home/.gradle
    -v "$HB/locks/claude-1000:/tmp/claude-1000")
}

cleanup() {
  trap '' INT TERM HUP
  [ -n "$TIMER" ] && kill "$TIMER" 2>/dev/null
  [ -n "$WATCH" ] && kill "$WATCH" 2>/dev/null
  if [ -n "$NAME" ]; then
    docker rm -f "$NAME" >/dev/null 2>&1
    # vfx-shot's own EXIT trap normally removes its compose stack; only after an abnormal end sweep this checkout's stacks.
    if [ "$KIND" = vfx ] && [ "$ABNORMAL" = 1 ]; then
      log "abnormal end: sweeping this checkout's shot stacks"
      docker rm -f "$NAME-reap" >/dev/null 2>&1
      base_args "$NAME-reap"
      timeout 180 docker run "${BASE_ARGS[@]}" -e SHOT_REAP_SCOPE=self -e ASTERA_HEAVY_INSIDE=1 "$IMAGE" \
        bash tools/vfx-shot/vfx-shot.sh reap >&2 </dev/null || true
    fi
  fi
  [ -n "$MERGED" ] && rm -f "$MERGED"
  [ -n "$SLOT_N" ] && rm -f "$HB/locks/slot.$SLOT_N.owner"
  exit "$RC"
}

cmd_run() {
  local id="${1:?id}" envfile="${3:?envfile}" main=$$ mem_need mem_cap cpus xmx t0=$SECONDS
  KIND="${2:?kind}"; shift 3
  [ "${1:-}" = -- ] && shift
  [ $# -gt 0 ] || die "no command given"
  case "$KIND" in vfx|gradle) ;; *) die "kind must be vfx|gradle" ;; esac
  load_heavy_env
  REPO="$HB/w/$id/repo"
  [ -d "$REPO" ] || die "$REPO missing (sync did not run)"
  docker image inspect "$IMAGE" >/dev/null 2>&1 \
    || die "runner image $IMAGE not found (or no docker access): run tools/remote/setup.sh (make heavy-setup) first"
  mkdir -p "$HB/home" "$HB/locks/claude-1000"

  acquire_slot
  NAME="astera-heavy-slot$SLOT_N"
  MERGED="$HB/w/$id/run.$$.merged.env"
  trap 'ABNORMAL=1; RC=143; exit 143' INT TERM HUP
  trap cleanup EXIT
  log "slot $SLOT_N acquired after $((SECONDS - t0))s"
  printf 'caller=%s kind=%s cmd=%.120s start=%s\n' \
    "$(sed -n 's/^ASTERA_HEAVY_CALLER=//p' "$envfile" | head -1)" "$KIND" "$*" "$(date '+%F %T')" > "$HB/locks/slot.$SLOT_N.owner"
  docker rm -f "$NAME" >/dev/null 2>&1   # leftover of a dead run: this slot is ours now

  if [ "$KIND" = vfx ]; then mem_need="${HEAVY_MIN_FREE_GB:-4}"; mem_cap="${HEAVY_MEM:-3g}"
  else mem_need="${HEAVY_MIN_FREE_GB:-3}"; mem_cap="${HEAVY_MEM:-4g}"; fi
  cpus="${HEAVY_CPUS:-6}"; xmx="${HEAVY_GRADLE_XMX:-2g}"
  memory_guard "$mem_need"

  # env for the runner: forwarded names + per-kind defaults (the one place for the small-VM tuning)
  cp "$envfile" "$MERGED"; rm -f "$envfile"
  add_default() { grep -q "^$1=" "$MERGED" || printf '%s=%s\n' "$1" "$2" >> "$MERGED"; }
  if [ "$KIND" = vfx ]; then
    sed -i '/^SHOT_KEEP=/d' "$MERGED"; echo 'SHOT_KEEP=0' >> "$MERGED"   # the runner is per run: nothing to keep warm
    add_default SHOT_MIN_FREE_GB 3
    add_default SHOT_CLIENT_XMX 1024m
    add_default LP_NUM_THREADS 3
    add_default SHOT_REAP_SCOPE host
    add_default SHOT_COMPOSE_EXTRA "$REPO/tools/remote/compose.heavy.yml"
  fi

  base_args "$NAME"
  local tty=(); [ -t 0 ] && tty=(-t)
  log "run: kind=$KIND mem=$mem_cap cpus=$cpus gradle-xmx=$xmx: $*"
  # Remote end of a dead ssh: with a pty the HUP arrives by itself; without one, stdin EOF means the client is gone.
  if [ ! -t 0 ] && [ "${HEAVY_NO_STDIN_WATCH:-0}" != 1 ]; then
    ( while IFS= read -r _; do :; done; kill -HUP "$main" 2>/dev/null ) &
    WATCH=$!
  fi
  if [ -n "${HEAVY_RUN_TIMEOUT:-7200}" ]; then
    ( sleep "${HEAVY_RUN_TIMEOUT:-7200}"; log "run timeout (HEAVY_RUN_TIMEOUT), stopping the runner"; docker rm -f "$NAME" >/dev/null 2>&1 ) &
    TIMER=$!
  fi
  docker run "${BASE_ARGS[@]}" "${tty[@]}" --env-file "$MERGED" \
    --memory "$mem_cap" --memory-swap "$mem_cap" --cpus "$cpus" --oom-score-adj 500 \
    -e "GRADLE_OPTS=-Dorg.gradle.daemon=false -Dorg.gradle.workers.max=$cpus -Dorg.gradle.jvmargs=-Xmx$xmx -XX:MaxMetaspaceSize=512m" \
    "$IMAGE" "$@" </dev/null {SLOT_FD}>&- &
  wait $!
  RC=$?
  log "run finished rc=$RC in $((SECONDS - t0))s"
  exit "$RC"
}

# ---------------------------------------------------------------- status
cmd_status() {
  local slots i f
  load_heavy_env
  slots="${HEAVY_SLOTS:-1}"
  echo "host       $(hostname), MemAvailable $(mem_available_gb) GB, base $HB"
  for i in $(seq 1 "$slots"); do
    f="$HB/locks/slot.$i.lock"
    if [ ! -e "$f" ] || flock -n "$f" true 2>/dev/null; then echo "slot $i     free"
    else echo "slot $i     busy: $(cat "$HB/locks/slot.$i.owner" 2>/dev/null)"; fi
  done
  echo "runners    $(docker ps --filter label=astera.heavy=1 --format '{{.Names}} ({{.Status}})' | tr '\n' ' ')"
  echo "shot stacks $(docker ps -a --filter name=astera-shot- --format '{{.Names}}' | wc -l) container(s)"
  echo "image      $(docker image inspect -f '{{.Id}} {{.Created}}' "$IMAGE" 2>/dev/null || echo "MISSING ($IMAGE): run setup")"
  echo "checkouts  $(ls "$HB/w" 2>/dev/null | tr '\n' ' ')"
  docker stats --no-stream --format '  {{.Name}}  cpu {{.CPUPerc}}  mem {{.MemUsage}}' 2>/dev/null | head -20
}

sub="${1:-}"; shift || true
case "$sub" in
  prepare) cmd_prepare "$@" ;;
  run) cmd_run "$@" ;;
  status) cmd_status ;;
  *) echo "usage: remote-exec.sh prepare <id> | run <id> <vfx|gradle> <envfile> -- cmd... | status" >&2; exit 2 ;;
esac
