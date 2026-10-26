#!/usr/bin/env bash
# watchdog.sh <run-pid> <state-dir> <timeout-s> [<caller-pid>:<starttime> ...]
#
# Started (setsid, its own session) by every vfx-shot / anim-shot run right after it took its locks, and it inherits the
# run's stack lock + slot fds on purpose: the stack stays "owned" (reap.py skips it, the slot stays taken) until this
# watchdog has finished the teardown, even when the run itself was SIGKILLed.
#   - SHOT_TIMEOUT: when the run is older than <timeout-s> it is stopped (TERM, KILL after 60 s).
#   - the caller (make / the agent's shell, recorded as pid:starttime) disappears -> the run is stopped the same way
#     (SHOT_DETACHED=1 at launch records no caller).
#   - the run is gone without having written <state>/run.done (its EXIT trap did not finish) -> full teardown of this
#     stack through reap.py (client process groups, Xvfb, parker, compose project), kept or not.
set -u
run="$1" state="$2" timeout="$3"
shift 3
HERE="$(cd "$(dirname "$0")" && pwd -P)"
wlog() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >>"$state/watchdog.log"; }
starttime() { sed 's/^.*) //' "/proc/$1/stat" 2>/dev/null | awk '{print $20}'; }
alive() { [ -n "$2" ] && [ "$(starttime "$1")" = "$2" ]; }

run_st="$(starttime "$run")"
deadline=$((SECONDS + timeout))
reason=""
wlog "watching run $run (timeout ${timeout}s, callers: ${*:-none})"
while alive "$run" "$run_st"; do
  if [ "$SECONDS" -ge "$deadline" ]; then reason="SHOT_TIMEOUT (${timeout}s) reached"; break; fi
  for c in "$@"; do
    alive "${c%%:*}" "${c#*:}" || { reason="caller ${c%%:*} is gone"; break 2; }
  done
  sleep 2
done

if [ -n "$reason" ]; then
  wlog "$reason: stopping run $run"
  : >"$state/abort"   # the run's cleanup treats an aborted run as SHOT_KEEP=0
  kill -TERM "$run" 2>/dev/null
  for _ in $(seq 1 60); do alive "$run" "$run_st" || break; sleep 1; done
  if alive "$run" "$run_st"; then wlog "run ignored TERM for 60 s: KILL"; kill -KILL "$run" 2>/dev/null; sleep 1; fi
fi

if [ -e "$state/run.done" ]; then wlog "run finished its own cleanup"; exit 0; fi
wlog "run ended without finishing its cleanup: tearing the stack down"
python3 -I "$HERE/reap.py" --only-state "$state" --force --lock-held >>"$state/watchdog.log" 2>&1
: >"$state/run.done"
wlog "teardown done"
