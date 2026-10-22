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

# Free X display number (no lock file / socket).
pick_display() {
  local n
  for n in $(seq 191 260); do
    [ -e "/tmp/.X${n}-lock" ] || [ -e "/tmp/.X11-unix/X${n}" ] || { echo "$n"; return 0; }
  done
  return 1
}
