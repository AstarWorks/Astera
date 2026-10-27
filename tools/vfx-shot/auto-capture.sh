#!/usr/bin/env bash
# Capture farm: when the dev autodeploy logs "deployed <sha>", film the skills that commit changed (top + side) on a warm,
# pinned vfx-shot stack and publish them to skill-compare as "auto <sha7>" - no agent action.
#
#   make vfx-auto-up | vfx-auto-down | vfx-auto-status | vfx-auto-once SHA=<sha> [BASE=<sha>]
#
# Stepped = slow motion (/tick rate 5): world, plugin scheduler and client share one clock; frames are mapped by the tick stamp.
# Flow per deployed sha (coalesced: if several land while one is filming, only the newest is processed, diffed from the last done sha):
#   1. changed-skills.py maps the changed content/{skills,vfx,animations,sounds,poses} files to skill ids
#   2. PREVIEW pass: realtime recording (STEPPED=0), published with --preview ("PREVIEW (realtime)" chip; skipped ticks tolerated)
#   3. STEPPED pass: slow motion (/tick rate 5), the last frame of every tick, published with --auto <sha>; replaces the preview. A stepped capture
#      with skipped ticks is retried once and then NOT published (publish-capture refuses it; the preview stays and says so in status).
# The farm owns a worktree (FARM_ROOT) = its own compose project + own slot lock (SHOT_LOCK), so authors' `make vfx-shot` runs are not
# queued behind it; its stack is pinned (reap.py never evicts it to make room). Idle JVMs are parked (SIGSTOP) by parker.sh.
set -uo pipefail

HERE="${FARM_HERE:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)}"   # FARM_HERE: set by the daemon snapshot (see run_loop)
TOOLS_ROOT="$(cd "$HERE/../.." && pwd -P)"          # where this script (and publish-capture.mjs) live
PRIMARY="${FARM_PRIMARY:-/IdeaProjects/Astera}"
FARM_ROOT="${FARM_ROOT:-$PRIMARY/.claude/worktrees/capture-farm}"
FARM_CHECKOUT="${FARM_CHECKOUT:-1}"                 # 1 = FARM_ROOT is detached onto each deployed sha (0: film whatever FARM_ROOT holds, tests)
DEPLOY_LOG="${FARM_DEPLOY_LOG:-$PRIMARY/.claude/state/dev-deploy/deploy.log}"
PUBLISHED="${FARM_PUBLISHED:-${SKILL_COMPARE_PUBLISHED:-$PRIMARY/build/skill-compare-published}}"
FSTATE="${FARM_STATE:-$PRIMARY/.claude/state/capture-farm}"
VIEWS="${FARM_VIEWS:-side,top}"
ALL_VIEWS="$VIEWS"
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
      SHOT_KEEP=1 SHOT_PIN=1 SHOT_KEEP_TTL="${FARM_TTL:-86400}" SHOT_OUTPUTS=lean SHOT_SLOW_RATE="${RATE:-${FARM_RATE:-2.5}}" \
      SHOT_LOCK="${SHOT_LOCK:-/tmp/claude-1000/astera-vfx-shot-farm.lock}" SHOT_REAP_SCOPE=self SHOT_DETACHED=1 \
      bash "$FARM_ROOT/tools/vfx-shot/vfx-shot.sh" capture ) >> "$FSTATE/shot.log" 2>&1
}

publish_failed() { # skill sha reason: record the failure for skill-compare (current.json is left alone)
  local v
  for v in ${VIEWS//,/ }; do
    node "$TOOLS_ROOT/tools/skill-compare/scripts/publish-capture.mjs" "$OUT_ROOT/$1/$v" "$PUBLISHED" "$1" "$v" --auto "$2" --failed "$3" >/dev/null 2>&1 || true
  done
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
  local skill="$1" sha="$2" mode="$3" v rc=0 flag=() out
  [ "$mode" = preview ] && flag=(--preview)
  local c; c="$(grep -l "\"asteraSkillId\": *\"$skill\"" "$FARM_ROOT"/tools/skill-compare/data/*/case.json 2>/dev/null | head -1)"
  [ -n "$c" ] && flag+=(--case "$(basename "$(dirname "$c")")")   # stamps the case's reference/content revision into the meta
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
    # FARM_ROOT holds the farm's own tools: never check out a sha that predates them (it would delete the running scripts)
    git -C "$FARM_ROOT" cat-file -e "$sha:tools/vfx-shot/auto-capture.sh" 2>/dev/null || { flog "skip ${sha:0:8}: predates the capture farm tooling"; return 0; }
    git -C "$FARM_ROOT" checkout -q --detach "$sha" 2>>"$FSTATE/farm.log" || { flog "checkout $sha failed"; return 1; }
  fi
  [ -n "$base" ] || base="$(cat "$FSTATE/done-sha" 2>/dev/null)"
  skills="$(python3 -I "$HERE/changed-skills.py" --root "$FARM_ROOT" "$base" "$sha")" || { flog "changed-skills failed ($base..$sha)"; return 1; }
  skills="$(printf '%s\n' "$skills" | sed '/^$/d' | head -n "$MAX_SKILLS")"
  flog "deployed ${sha:0:8} (log $deploy_ts) base=${base:0:8} skills: $(echo $skills)"
  if [ -n "$skills" ]; then
    if [ "$PREVIEW" = 1 ]; then
      for s in $skills; do
        # a preview only fills an empty slot (publish-capture keeps stepped captures): a skill that already has captures skips the pass
        if ls "$PUBLISHED/$s"/*/current.json >/dev/null 2>&1 && [ -n "$(ls "$PUBLISHED/$s"/{side,top}/current.json 2>/dev/null)" ]; then
          flog "PREVIEW $s skipped: already published, the stepped pass replaces it"; continue
        fi
        ticks="$(ticks_for "$s")"; tp="$(now)"
        if shot realtime "$s" "$ticks" && publish "$s" "$sha" preview; then
          flog "PREVIEW $s published $(awk -v a="$tp" -v b="$(now)" 'BEGIN{printf "%.0f", b-a}')s (capture), $(awk -v a="$t0" -v b="$(now)" 'BEGIN{printf "%.0f", b-a}')s since detection"
        else flog "PREVIEW $s failed (see shot.log)"; fi
      done
    fi
    for s in $skills; do
      # one view at a time, in FARM_VIEWS order: the first view (side) is published before the next is filmed
      for v1 in ${ALL_VIEWS//,/ }; do
        VIEWS="$v1"
        ticks="$(ticks_for "$s")"; tstep="$(now)"; n=0
        while :; do
          # a view that drops ticks at this tick rate (the software client hitches on heavy VFX, mostly side) is refilmed at half the rate
          rate="$(awk -v r="${FARM_RATE:-2.5}" -v n="$n" 'BEGIN{for (i = 0; i < n; i++) r /= 2; if (r < 1) r = 1; print r}')"
          if RATE="$rate" shot stepped "$s" "$ticks" && publish "$s" "$sha" stepped; then
            flog "STEPPED $s/$v1 published $(awk -v a="$tstep" -v b="$(now)" 'BEGIN{printf "%.0f", b-a}')s (capture), $(awk -v a="$t0" -v b="$(now)" 'BEGIN{printf "%.0f", b-a}')s since detection"
            summary="$summary $s/$v1:ok"; break
          fi
          n=$((n + 1))
          if [ "$n" -gt "$RETRIES" ]; then
            flog "STEPPED $s/$v1 not published after $n attempt(s); the last valid capture stays as the old version"
            publish_failed "$s" "$sha" "stepped capture dropped ticks / invalid after $n attempt(s) (last rate $rate tps)"
            summary="$summary $s/$v1:FAILED"; break
          fi
          flog "STEPPED $s/$v1 attempt $n failed at $rate tps; retrying at half the rate"
        done
      done
      VIEWS="$ALL_VIEWS"
    done
  fi
  echo "$sha" > "$FSTATE/done-sha"
  printf '{"sha":"%s","base":"%s","deployLogAt":"%s","finishedAt":"%s","seconds":%.0f,"skills":"%s"}\n' \
    "$sha" "$base" "$deploy_ts" "$(stamp)" "$(awk -v a="$t0" -v b="$(now)" 'BEGIN{print b-a}')" "$(echo $summary)" > "$FSTATE/last-run.json"
  flog "done ${sha:0:8}:$summary"
}

latest_deployed() { grep -E ' deployed [0-9a-f]{7,40}( |$)' "$DEPLOY_LOG" 2>/dev/null | tail -1 | awk '{print $3}'; }

run_loop() {
  # bash reads a script lazily and the checkouts below rewrite this very file: run from a private copy
  if [ -z "${FARM_HERE:-}" ]; then
    cp "$0" "$FSTATE/auto-capture.run.sh" && FARM_HERE="$HERE" exec bash "$FSTATE/auto-capture.run.sh" run
  fi
  echo $$ > "$FSTATE/daemon.pid"
  trap '[ "$(cat "$FSTATE/daemon.pid" 2>/dev/null)" = "$$" ] && rm -f "$FSTATE/daemon.pid"; exit 0' TERM INT
  local sha
  # first start: do not replay history, start from what is deployed now
  [ -f "$FSTATE/done-sha" ] || latest_deployed > "$FSTATE/done-sha"
  flog "farm up: root=$FARM_ROOT log=$DEPLOY_LOG published=$PUBLISHED views=$VIEWS from $(cut -c1-8 "$FSTATE/done-sha")"
  if [ "${FARM_WARM:-1}" = 1 ] && [ "$FARM_CHECKOUT" = 1 ]; then
    git -C "$FARM_ROOT" cat-file -e "$(cat "$FSTATE/done-sha"):tools/vfx-shot/auto-capture.sh" 2>/dev/null \
      && git -C "$FARM_ROOT" checkout -q --detach "$(cat "$FSTATE/done-sha")" 2>/dev/null
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
