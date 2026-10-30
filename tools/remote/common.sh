#!/usr/bin/env bash
# Shared by run.sh and setup.sh (dev-host side). Sourced; no side effects.

HEAVY_DEFAULT_HOST="astera@192.168.14.3"
HEAVY_IMAGE="${HEAVY_IMAGE:-astera/heavy-runner:local}"

hlog() { printf '[heavy] %s\n' "$*" >&2; }
hdie() { hlog "ERROR: $*"; exit 1; }

# Target: unset -> default VM, set-but-empty -> "" (= run locally).
heavy_host() { printf '%s' "${ASTERA_HEAVY_HOST-$HEAVY_DEFAULT_HOST}"; }

# One multiplexed ssh connection per host: sync, run and pull reuse it.
heavy_ssh_opts() {
  mkdir -p "$HOME/.ssh" && chmod 700 "$HOME/.ssh" 2>/dev/null || true
  printf '%s' "-o BatchMode=yes -o ConnectTimeout=10 -o ControlMaster=auto -o ControlPath=$HOME/.ssh/astera-heavy-%C -o ControlPersist=60"
}

# Absolute remote base dir for a host-relative ASTERA_HEAVY_BASE (resolved on the remote; this prints the shell snippet).
heavy_remote_base_snippet() {
  printf 'HB=%q; case "$HB" in /*) ;; *) HB="$HOME/$HB";; esac' "${ASTERA_HEAVY_BASE:-astera-heavy}"
}

heavy_ssh_hint() {
  hlog "ssh to $1 failed. Check: ssh $1 true  (key, network, host up)."
  hlog "To run on this machine instead: ASTERA_HEAVY_HOST= make <target>   (only while astera-mc is down)"
}
