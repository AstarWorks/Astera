#!/usr/bin/env bash
# parker.sh <state-dir>: keeps the parked capture clients of a kept (SHOT_KEEP=1) stack cheap, and bounded.
#
# A joined software-GL Minecraft client renders continuously: about 2 cores each even when nobody looks. Between
# captures this loop freezes every parked client JVM (SIGSTOP) and thaws it for a short window (SIGCONT) so the netty
# thread still answers the server's keepalive (the server drops a client after 30 s of silence; the default cycle
# is 2 s running / 8 s stopped, so the longest silence is 8 s plus a scheduling hiccup). While a capture runs
# (<state-dir>/capture.active exists) every client is kept running. The loop runs until <state-dir>/parker.on is removed and
# thaws everything on the way out, so a dead parker never leaves a frozen client behind.
#
# TTL: once <state-dir>/keep-until (epoch, written by the run that kept the stack) is in the past or gone while no capture
# runs, the parker hands over to reap.py, which tears the stack down unless a run holds its lock again.
set -u
state="$1"
HERE="$(cd "$(dirname "$0")" && pwd -P)"
run="${PARK_RUN:-2}"
stop="${PARK_STOP:-8}"

client_jvms() {
  local f cp
  for f in "$state"/clients/*.env; do
    [ -f "$f" ] || continue
    cp="$(sed -n 's/^CPID=//p' "$f")"
    [ -n "$cp" ] && pgrep -P "$cp" java 2>/dev/null
  done
}
signal_all() { local p; for p in $(client_jvms); do kill "-$1" "$p" 2>/dev/null; done; }
expired() {
  local ku
  [ -e "$state/capture.active" ] && return 1
  ku="$(cat "$state/keep-until" 2>/dev/null)"
  [ -z "$ku" ] || [ "$(date +%s)" -ge "${ku%.*}" ]
}

trap 'signal_all CONT; exit 0' EXIT TERM INT HUP
while [ -e "$state/parker.on" ]; do
  signal_all CONT
  if expired; then
    rm -f "$state/parker.on" "$state/parker.pid"
    trap - EXIT
    exec python3 -I "$HERE/reap.py" --only-state "$state" >>"$state/parker.log" 2>&1
  fi
  if [ -e "$state/capture.active" ]; then sleep 1; continue; fi
  sleep "$run"
  [ -e "$state/capture.active" ] && continue
  signal_all STOP
  # a capture may start while the clients are frozen: poll so it never waits a whole stop window
  for _ in $(seq 1 "$stop"); do
    [ -e "$state/capture.active" ] && break
    [ -e "$state/parker.on" ] || break
    sleep 1
  done
done
