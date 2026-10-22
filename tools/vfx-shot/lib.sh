#!/usr/bin/env bash
# vfx-shot shared helpers (sourced by vfx-shot.sh). No side effects on source.

log() { printf '[vfx-shot] %s\n' "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

# Pick a /24 that docker accepts: try 10.N.77.0/24 (N = 211..250) with a throwaway network.
pick_subnet_prefix() {
  local n
  for n in $(seq "${1:-211}" 250); do
    if docker network create --subnet "10.$n.77.0/24" "astera-shot-probe-$$" >/dev/null 2>&1; then
      docker network rm "astera-shot-probe-$$" >/dev/null 2>&1
      echo "10.$n.77"
      return 0
    fi
  done
  return 1
}

# Global concurrency limit across worktrees/agents: SHOT_SLOTS (default 2) flock slots, held on a file descriptor
# for the life of the process (the kernel releases them when the run ends, however it ends). Heavy parts (gradle in
# the docker build, server, two Minecraft clients) all run inside the slot.
acquire_slot() {
  local dir="${SHOT_SLOT_DIR:-/tmp/claude-1000/vfx-shot-slots}" n i fd announced=0
  n="${SHOT_SLOTS:-2}"
  mkdir -p "$dir" || die "cannot create $dir"
  while :; do
    for i in $(seq 1 "$n"); do
      exec {fd}>"$dir/slot-$i.lock" || die "cannot open $dir/slot-$i.lock"
      if flock -n "$fd"; then
        log "slot $i/$n acquired"
        return 0
      fi
      exec {fd}>&-
    done
    [ "$announced" = 1 ] || { log "queued: all $n slots busy ($dir), waiting"; announced=1; }
    sleep 3
  done
}

# with_global_lock <name> <command...>: run a short critical section under an exclusive cross-run lock.
with_global_lock() {
  local name="$1" dir="${SHOT_SLOT_DIR:-/tmp/claude-1000/vfx-shot-slots}" fd rc
  shift
  mkdir -p "$dir"
  exec {fd}>"$dir/$name.lock" || return 1
  flock "$fd"
  "$@"; rc=$?
  exec {fd}>&-
  return "$rc"
}
