#!/usr/bin/env bash
# parker.sh <state-dir>: keeps the parked capture clients cheap.
#
# A joined software-GL Minecraft client renders continuously: about 2 cores each even when nobody looks. Between
# captures this loop freezes every parked client JVM (SIGSTOP) and thaws it for a short window (SIGCONT) so the netty
# thread still answers the server's keepalive (the server drops a client after 30 s of silence; the default cycle
# is 2 s running / 8 s stopped, so the longest silence is 8 s plus a scheduling hiccup). While a capture runs
# (<state-dir>/capture.active exists) every client is kept running. The loop runs until <state-dir>/parker.on is removed and
# thaws everything on the way out, so a dead parker never leaves a frozen client behind.
set -u
state="$1"
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

trap 'signal_all CONT; exit 0' EXIT TERM INT
while [ -e "$state/parker.on" ]; do
  signal_all CONT
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
