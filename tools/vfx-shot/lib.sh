#!/usr/bin/env bash
# vfx-shot shared helpers (sourced by vfx-shot.sh). No side effects on source.

# fds of the held capture locks. Long-lived children (parked Xvfb / clients) must not inherit them (a flock lives as long as
# any process holds the open file description), so they are started with `{STACK_LOCK_FD}>&- {SLOT_FD}>&-`. 99 = unused.
STACK_LOCK_FD=99
SLOT_FD=99

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
# for the life of the process (the kernel releases them when the run ends, however it ends). The slots are
# vfx-shot's own semaphore: they never touch the host-wide e2e lock (/tmp/claude-1000/astera-e2e.lock).
# A capture (clients rendering with llvmpipe + ffmpeg) runs inside a slot; the warm stack itself sits outside it.
acquire_slot() {
  local dir="${SHOT_SLOT_DIR:-/tmp/claude-1000/vfx-shot-slots}" n i fd announced=0
  n="${SHOT_SLOTS:-2}"
  mkdir -p "$dir" || die "cannot create $dir"
  while :; do
    for i in $(seq 1 "$n"); do
      exec {fd}>"$dir/slot-$i.lock" || die "cannot open $dir/slot-$i.lock"
      if flock -n "$fd"; then
        SLOT_FD="$fd"
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

# Memory guard (replaces "wrap every capture in the e2e flock"): the e2e lock exists because of host OOM, not because
# capture stacks share anything with e2e. A warm stack is ~6-7 GB resident (2 GB server heap + 2 clients), so refuse
# to start a server or a client while the host has less than SHOT_MIN_FREE_GB (default 8) available.
mem_available_gb() { awk '/^MemAvailable:/ {printf "%d", $2 / 1048576}' /proc/meminfo; }

memory_guard() { # <what>
  local need="${SHOT_MIN_FREE_GB:-8}" wait="${SHOT_MEM_WAIT:-900}" deadline announced=0 have
  deadline=$((SECONDS + wait))
  while :; do
    have="$(mem_available_gb)"
    [ "$have" -ge "$need" ] && return 0
    [ "$announced" = 1 ] || { log "memory guard: ${have} GB available < ${need} GB, waiting before $1"; announced=1; }
    [ "$SECONDS" -lt "$deadline" ] || die "memory guard: still only ${have} GB available after ${wait}s (SHOT_MIN_FREE_GB=$need)"
    sleep 5
  done
}

# One capture at a time per checkout (= per warm stack): queues on $1; held on an fd until the process exits.
acquire_stack_lock() { # <lockfile>
  local fd
  exec {fd}>"$1" || die "cannot open $1"
  if ! flock -n "$fd"; then
    log "queued: another capture is using this checkout's stack ($1), waiting"
    flock "$fd"
  fi
  STACK_LOCK_FD="$fd"
}

# Hash of a directory tree, in the exact pipeline the container runs too (compare host staging with /content).
tree_hash() { # <dir>
  (cd "$1" && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -c1-16)
}

# Elapsed-seconds trace, one line per phase, so the next speed decision is measured.
PHASE_T0=$SECONDS
phase() { log "[+$((SECONDS - PHASE_T0))s] $*"; }
