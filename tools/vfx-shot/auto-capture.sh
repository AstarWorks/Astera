#!/usr/bin/env bash
# Capture farm: when the dev autodeploy logs "deployed <sha>", film the skills that commit changed (top + side) on a warm,
# pinned vfx-shot stack and publish them to skill-compare as "auto <sha7>" - no agent action.
#
#   make vfx-auto-up | vfx-auto-down | vfx-auto-status | vfx-auto-once SHA=<sha> [BASE=<sha>]
#
# Flow per deployed sha (coalesced: if several land while one is filming, only the newest is processed, diffed from the last done sha):
#   1. changed-skills.py maps the changed content/{skills,vfx,animations,sounds,poses} files to skill ids
#   2. PREVIEW pass: realtime recording (STEPPED=0), published with --preview ("PREVIEW (realtime)" chip; skipped ticks tolerated)
#   3. STEPPED pass: freeze + /tick step, exact one frame per tick, published with --auto <sha>; replaces the preview. A stepped capture
#      with skipped ticks is retried once and then NOT published (publish-capture refuses it; the preview stays and says so in status).
# The farm owns a worktree (FARM_ROOT) = its own compose project + own slot lock (SHOT_LOCK), so authors' `make vfx-shot` runs are not
# queued behind it; its stack is pinned (reap.py never evicts it to make room). Idle JVMs are parked (SIGSTOP) by parker.sh.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
TOOLS_ROOT="$(cd "$HERE/../.." && pwd -P)"          # where this script (and publish-capture.mjs) live
PRIMARY="${FARM_PRIMARY:-/IdeaProjects/Astera}"
FARM_ROOT="${FARM_ROOT:-$PRIMARY/.claude/worktrees/capture-farm}"
FARM_CHECKOUT="${FARM_CHECKOUT:-1}"                 # 1 = FARM_ROOT is detached onto each deployed sha (0: film whatever FARM_ROOT holds, tests)
DEPLOY_LOG="${FARM_DEPLOY_LOG:-$PRIMARY/.claude/state/dev-deploy/deploy.log}"
PUBLISHED="${FARM_PUBLISHED:-${SKILL_COMPARE_PUBLISHED:-$PRIMARY/build/skill-compare-published}}"
FSTATE="${FARM_STATE:-$PRIMARY/.claude/state/capture-farm}"
VIEWS="${FARM_VIEWS:-top,side}"
TICKS_DEFAULT="${FARM_TICKS:-80}"
POLL="${FARM_POLL:-5}"
MAX_SKILLS="${FARM_MAX_SKILLS:-8}"
PREVIEW="${FARM_PREVIEW:-1}"
RETRIES="${FARM_RETRIES:-1}"
OUT_ROOT="${FARM_OUT:-$FARM_ROOT/build/vfx-shot}"
mkdir -p "$FSTATE"

now() { date +%s.%N; }
stamp() { date +%Y-%m-%dT%H:%M:%S%z; }
flog() { printf '%s %s\n' "$(stamp)" "$*" | tee -a "$FSTATE/farm.log" >&2; }

# The farm's vfx-shot env: pinned warm stack, own slot, lean outputs, short dwell, never evicts other checkouts' stacks.
shot() { # mode(stepped|realtime) skill ticks
  local mode="$1" skill="$2" ticks="$3" stepped=1
  [ "$mode" = realtime ] && stepped=0
  ( cd "$FARM_ROOT" && env SKILL="$skill" VIEW="$VIEWS" TICKS="$ticks" STEPPED="$stepped" FPS=20 OUT_ROOT="$OUT_ROOT" \
      SHOT_KEEP=1 SHOT_PIN=1 SHOT_KEEP_TTL="${FARM_TTL:-86400}" SHOT_OUTPUTS=lean SHOT_STEP_DWELL="${FARM_DWELL:-0.15}" \
      SHOT_LOCK="${SHOT_LOCK:-/tmp/claude-1000/astera-vfx-shot-farm.lock}" SHOT_REAP_SCOPE=self SHOT_DETACHED=1 \
      bash "$FARM_ROOT/tools/vfx-shot/vfx-shot.sh" capture ) >> "$FSTATE/shot.log" 2>&1
}

ticks_for() { # skill: frames of an already published view (the author's chosen length), else the default
  local v f n
  for v in ${VIEWS//,/ } compare; do
    f="$PUBLISHED/$1/$v/current.json"
    [ -f "$f" ] || continue
    n="$(jq -r '.meta.frames // empty' "$f" 2>/dev/null)"
    [ -n "$n" ] && { echo "$n"; return; }
  done
  echo "$TICKS_DEFAULT"
}

publish() { # skill sha mode(preview|stepped)  -> 0 when every view was published
  local skill="$1" sha="$2" mode="$3" v rc=0 flag=()
  [ "$mode" = preview ] && flag=(--preview)
  for v in ${VIEWS//,/ }; do
    if ! out="$(node "$TOOLS_ROOT/tools/skill-compare/scripts/publish-capture.mjs" "$OUT_ROOT/$skill/$v" "$PUBLISHED" "$skill" "$v" --auto "$sha" "${flag[@]}" 2>&1)"; then
      flog "publish $skill/$v ($mode) REFUSED: $out"; rc=1
    fi
  done
  return $rc
}

process() { # sha [base]
  local sha="$1" base="${2:-}" t0 deploy_ts skills s ticks tp tstep summary="" n=0
  t0="$(now)"
  deploy_ts="$(grep " deployed ${sha}" "$DEPLOY_LOG" 2>/dev/null | tail -1 | cut -d' ' -f1)"
  if [ "$FARM_CHECKOUT" = 1 ]; then
    git -C "$FARM_ROOT" cat-file -e "$sha^{commit}" 2>/dev/null || git -C "$FARM_ROOT" fetch -q origin 2>/dev/null
    git -C "$FARM_ROOT" checkout -q --detach "$sha" 2>>"$FSTATE/farm.log" || { flog "checkout $sha failed"; return 1; }
  fi
  [ -n "$base" ] || base="$(cat "$FSTATE/done-sha" 2>/dev/null)"
  skills="$(python3 -I "$HERE/changed-skills.py" --root "$FARM_ROOT" "$base" "$sha")" || { flog "changed-skills failed ($base..$sha)"; return 1; }
  skills="$(printf '%s\n' "$skills" | sed '/^$/d' | head -n "$MAX_SKILLS")"
  flog "deployed ${sha:0:8} (log $deploy_ts) base=${base:0:8} skills: $(echo $skills)"
  if [ -n "$skills" ]; then
    if [ "$PREVIEW" = 1 ]; then
      for s in $skills; do
        ticks="$(ticks_for "$s")"; tp="$(now)"
        if shot realtime "$s" "$ticks" && publish "$s" "$sha" preview; then
          flog "PREVIEW $s published $(awk -v a="$tp" -v b="$(now)" 'BEGIN{printf "%.0f", b-a}')s (capture), $(awk -v a="$t0" -v b="$(now)" 'BEGIN{printf "%.0f", b-a}')s since detection"
        else flog "PREVIEW $s failed (see shot.log)"; fi
      done
    fi
    for s in $skills; do
      ticks="$(ticks_for "$s")"; tstep="$(now)"; n=0
      while :; do
        if shot stepped "$s" "$ticks" && publish "$s" "$sha" stepped; then
          flog "STEPPED $s published $(awk -v a="$tstep" -v b="$(now)" 'BEGIN{printf "%.0f", b-a}')s (capture), $(awk -v a="$t0" -v b="$(now)" 'BEGIN{printf "%.0f", b-a}')s since detection"
          summary="$summary $s:ok"; break
        fi
        n=$((n + 1))
        if [ "$n" -gt "$RETRIES" ]; then flog "STEPPED $s not published after $n attempt(s); the preview (if any) stays"; summary="$summary $s:FAILED"; break; fi
        flog "STEPPED $s attempt $n failed; retrying"
      done
    done
  fi
  echo "$sha" > "$FSTATE/done-sha"
  printf '{"sha":"%s","base":"%s","deployLogAt":"%s","finishedAt":"%s","seconds":%.0f,"skills":"%s"}\n' \
    "$sha" "$base" "$deploy_ts" "$(stamp)" "$(awk -v a="$t0" -v b="$(now)" 'BEGIN{print b-a}')" "$(echo $summary)" > "$FSTATE/last-run.json"
  flog "done ${sha:0:8}:$summary"
}

latest_deployed() { grep -E ' deployed [0-9a-f]{7,40}( |$)' "$DEPLOY_LOG" 2>/dev/null | tail -1 | awk '{print $3}'; }

run_loop() {
  echo $$ > "$FSTATE/daemon.pid"
  trap 'rm -f "$FSTATE/daemon.pid"; exit 0' TERM INT
  local sha
  # first start: do not replay history, start from what is deployed now
  [ -f "$FSTATE/done-sha" ] || latest_deployed > "$FSTATE/done-sha"
  flog "farm up: root=$FARM_ROOT log=$DEPLOY_LOG published=$PUBLISHED views=$VIEWS from $(cut -c1-8 "$FSTATE/done-sha")"
  if [ "${FARM_WARM:-1}" = 1 ] && [ "$FARM_CHECKOUT" = 1 ]; then
    git -C "$FARM_ROOT" checkout -q --detach "$(cat "$FSTATE/done-sha")" 2>/dev/null
    ( cd "$FARM_ROOT" && env VIEW=side OUT_ROOT="$OUT_ROOT" SHOT_PIN=1 SHOT_KEEP_TTL="${FARM_TTL:-86400}" SHOT_OUTPUTS=lean \
        SHOT_LOCK="${SHOT_LOCK:-/tmp/claude-1000/astera-vfx-shot-farm.lock}" SHOT_REAP_SCOPE=self SHOT_DETACHED=1 \
        bash "$FARM_ROOT/tools/vfx-shot/vfx-shot.sh" up ) >> "$FSTATE/shot.log" 2>&1 && flog "warm stack up"
  fi
  while :; do
    sha="$(latest_deployed)"
    if [ -n "$sha" ] && [ "$sha" != "$(cat "$FSTATE/done-sha" 2>/dev/null)" ]; then
      process "$sha" || { echo "$sha" > "$FSTATE/done-sha"; flog "process failed for ${sha:0:8}; skipped"; }
    fi
    sleep "$POLL"
  done
}

case "${1:-status}" in
  run) run_loop ;;
  once) # once <sha> [base]: process one commit now (manual / tests)
    [ -n "${2:-}" ] || { echo "usage: auto-capture.sh once <sha> [base]"; exit 2; }
    process "$2" "${3:-}" ;;
  up)
    if [ -f "$FSTATE/daemon.pid" ] && kill -0 "$(cat "$FSTATE/daemon.pid")" 2>/dev/null; then echo "farm already running (pid $(cat "$FSTATE/daemon.pid"))"; exit 0; fi
    if [ ! -d "$FARM_ROOT" ] && [ "$FARM_CHECKOUT" = 1 ]; then
      git -C "$PRIMARY" fetch -q origin && git -C "$PRIMARY" worktree add --detach "$FARM_ROOT" origin/main || exit 1
    fi
    setsid nohup bash "$HERE/auto-capture.sh" run >> "$FSTATE/daemon.out" 2>&1 < /dev/null &
    echo "farm started (pid $!); log: $FSTATE/farm.log" ;;
  down)
    [ -f "$FSTATE/daemon.pid" ] && kill "$(cat "$FSTATE/daemon.pid")" 2>/dev/null
    rm -f "$FSTATE/daemon.pid"
    ( cd "$FARM_ROOT" && env OUT_ROOT="$OUT_ROOT" SHOT_LOCK="${SHOT_LOCK:-/tmp/claude-1000/astera-vfx-shot-farm.lock}" \
        bash "$FARM_ROOT/tools/vfx-shot/vfx-shot.sh" down ) 2>&1 | tail -3
    echo "farm stopped" ;;
  status)
    if [ -f "$FSTATE/daemon.pid" ] && kill -0 "$(cat "$FSTATE/daemon.pid")" 2>/dev/null; then echo "daemon: running (pid $(cat "$FSTATE/daemon.pid"))"; else echo "daemon: stopped"; fi
    echo "done sha: $(cat "$FSTATE/done-sha" 2>/dev/null) | deployed now: $(latest_deployed)"
    [ -f "$FSTATE/last-run.json" ] && echo "last run: $(cat "$FSTATE/last-run.json")"
    ( cd "$FARM_ROOT" 2>/dev/null && env OUT_ROOT="$OUT_ROOT" bash "$FARM_ROOT/tools/vfx-shot/vfx-shot.sh" status 2>&1 | tail -4 )
    echo "--- farm.log"; tail -n "${LINES_TAIL:-12}" "$FSTATE/farm.log" 2>/dev/null ;;
  *) echo "usage: auto-capture.sh run|once <sha> [base]|up|down|status"; exit 2 ;;
esac
