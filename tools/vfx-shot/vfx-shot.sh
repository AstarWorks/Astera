#!/usr/bin/env bash
# vfx-shot: cast one skill on an Astera server and film it with a REAL Minecraft client (headless: Xvfb + llvmpipe).
# Output: build/vfx-shot/<skill>/<view>/{frame_NNN.png,sheet.png,<skill>-<view>.gif,.mp4,meta.json}
#
#   make vfx-shot SKILL=couture-rapier-s2 [VIEW=caster|opponent|side|front|back|right|high|near|top|compare|all] [TICKS=60]
#   orbit views (spectator Watcher): side = FOV/DISTANCE-framed off the caster's right (screen-right = forward), right = the opposite side, front = ahead beside
#   the target looking back, back = behind the caster, high = 45 deg down from the back-left, near = a bystander at eye
#   height ~2.4 blocks away (orbit_cam)
#   make vfx-shot SKILL=bastion-greatsword-s3 VIEW=compare CASE=zwei-s3     # camera from the skill-compare intent
#   make vfx-shot-up | vfx-shot-status | vfx-shot-down | vfx-shot-reap       # the kept stack, explicitly / sweep stragglers
#
# LIFECYCLE. One stack per checkout (compose project astera-shot-<hash of the checkout path>): a Paper server +
# resource-pack host + the clients (Watcher, Caster). By default a run tears down everything it started on every exit
# path (clients' whole process groups, the compose project); a watchdog (watchdog.sh) enforces SHOT_TIMEOUT and finishes
# the teardown when the run dies without its EXIT trap. Runs are serialized host-wide (SHOT_SLOTS, default 1, flock on
# /tmp/claude-1000/astera-vfx-shot.lock) and every run first reaps stragglers of dead runs (reap.py).
# SHOT_KEEP=1 keeps the stack + parked clients warm for the next capture from this checkout (only what changed is synced:
# content/ -> docker cp + `astera admin reload all`; pack inputs -> host gradle + docker cp + pack reload; plugin code ->
# host gradle + jar copy + server restart), but only for SHOT_KEEP_TTL seconds after the run and only while no other
# checkout needs the slot; then the parker / the next run's reaper removes it.
# See tools/vfx-shot/README.md for the knobs and troubleshooting.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd "$HERE/../.." && pwd -P)"
# shellcheck source=lib.sh
. "$HERE/lib.sh"
drop_inherited_locks

SOURCED="${VFX_SHOT_SOURCED:-0}"     # anim-shot.sh sources this file for the functions only
CMD="${1:-capture}"                  # capture | up | down | status
SKILL="${SKILL:-}"
CASE="${CASE:-}"                     # VIEW=compare: skill-compare case id (tools/skill-compare/data/<case>/intent.json)
OUT_ROOT="${OUT_ROOT:-$ROOT/build/vfx-shot}"
# Stack identity. The warm stack is per checkout (one stack per worktree, shared by every skill/case filmed there);
# a sourced run (anim-shot) keeps its own throwaway stack named after the run.
if [ "$SOURCED" = 1 ]; then
  SHOT_ID="${SHOT_ID:-$(printf '%s|%s' "$ROOT" "${CASE:-$SKILL}" | sha1sum | cut -c1-8)}"
else
  SHOT_ID="${SHOT_ID:-$(printf '%s' "$ROOT" | sha1sum | cut -c1-8)}"
fi
PROJECT="${SHOT_PROJECT:-astera-shot-$SHOT_ID}"
STATE="$OUT_ROOT/.state/$SHOT_ID"
CLIENTS="$OUT_ROOT/.clients/$SHOT_ID"
mkdir -p "$STATE/clients" "$CLIENTS"

SERVER_CT="$PROJECT-e2e-1"
PACK_CT="$PROJECT-e2e-resource-pack-1"
NET="${PROJECT}_default"
RCON_PORT=25575
RCON_PASS="astera-shot"
SELF_CT="$(hostname)"
SERVER_IP=""
SUBNET=""
declare -A DISP XPID CPID

compose() { # SHOT_COMPOSE_EXTRA=<file>: one more compose file on top (tools/remote sets memory caps for astera-mc)
  local extra=(); [ -n "${SHOT_COMPOSE_EXTRA:-}" ] && extra=(-f "$SHOT_COMPOSE_EXTRA")
  SHOT_SUBNET_PREFIX="$SUBNET" ASTERA_E2E_ID="$SHOT_ID" RCON_PASSWORD="$RCON_PASS" \
    docker compose -p "$PROJECT" -f "$ROOT/docker/docker-compose.e2e.yml" \
    -f "$HERE/compose.override.yml" "${extra[@]}" --profile e2e "$@"
}
rcon() { python3 -I "$HERE/rcon.py" "$SERVER_IP" "$RCON_PORT" "$RCON_PASS" "$@"; }

load_subnet() {
  [ -n "$SUBNET" ] && return 0
  SUBNET="$(cat "$STATE/subnet" 2>/dev/null || true)"
  SERVER_IP="${SUBNET:+$SUBNET.2}"
  [ -n "$SUBNET" ]
}

# ---------------------------------------------------------------- parking (cheap parked clients, see parker.sh)
client_jvms() { # pids of the parked clients' JVMs
  local f cp
  for f in "$STATE"/clients/*.env; do
    [ -f "$f" ] || continue
    cp="$(sed -n 's/^CPID=//p' "$f")"
    [ -n "$cp" ] && pgrep -P "$cp" java 2>/dev/null
  done
}

thaw_clients() { local p; for p in $(client_jvms); do kill -CONT "$p" 2>/dev/null; done; }

park_start() { # idempotent: one parker per stack, detached from our locks
  ls "$STATE"/clients/*.env >/dev/null 2>&1 || return 0
  : > "$STATE/parker.on"
  if [ -f "$STATE/parker.pid" ] && kill -0 "$(cat "$STATE/parker.pid")" 2>/dev/null; then return 0; fi
  setsid nohup bash "$HERE/parker.sh" "$STATE" >/dev/null 2>&1 {STACK_LOCK_FD}>&- {SLOT_FD}>&- < /dev/null &
  echo $! > "$STATE/parker.pid"
}

park_stop() {
  rm -f "$STATE/parker.on"
  [ -f "$STATE/parker.pid" ] && kill "$(cat "$STATE/parker.pid")" 2>/dev/null
  rm -f "$STATE/parker.pid" "$STATE/capture.active"
  thaw_clients
}

# ---------------------------------------------------------------- down (no capture config needed)
stop_client_state() { # name: kill a parked client recorded in the state dir
  local f="$STATE/clients/$1.env" CPID_ XPID_
  [ -f "$f" ] || return 0
  CPID_="$(sed -n 's/^CPID=//p' "$f")"; XPID_="$(sed -n 's/^XPID=//p' "$f")"
  [ -n "$CPID_" ] && kill -TERM -- "-$CPID_" 2>/dev/null
  [ -n "$XPID_" ] && kill "$XPID_" 2>/dev/null
  sleep 0.5
  [ -n "$CPID_" ] && kill -KILL -- "-$CPID_" 2>/dev/null
  rm -f "$f"
}

stack_down() {
  local f n
  park_stop
  for f in "$STATE"/clients/*.env; do
    [ -f "$f" ] || continue
    n="$(basename "$f" .env)"
    stop_client_state "$n"
  done
  load_subnet || SUBNET=10.211.77
  docker network disconnect "$NET" "$SELF_CT" >/dev/null 2>&1 || true
  compose down -v --remove-orphans >/dev/null 2>&1
  rm -f "$STATE/subnet" "$STATE/deployed.json"
  log "stack $PROJECT removed"
}

# ---------------------------------------------------------------- run lifecycle (lock, watchdog, reap, teardown)
SHOT_TIMEOUT="${SHOT_TIMEOUT:-2700}"          # hard wall-clock limit per run (s), counted from the moment it holds its locks
SHOT_KEEP_TTL="${SHOT_KEEP_TTL:-$SHOT_TIMEOUT}"   # SHOT_KEEP=1: how long the kept stack may idle after the run (s)
SHOT_REGISTRY="${SHOT_REGISTRY:-/tmp/claude-1000/astera-vfx-shot/stacks}"
export SHOT_TIMEOUT SHOT_KEEP_TTL SHOT_REGISTRY
RUN_STARTED=0
WATCHDOG_PID=""
FILM_PID=""

reap_py() { python3 -I "$HERE/reap.py" "$@"; }

# Host sweep for stragglers of dead runs + room for this run (SHOT_REAP_SCOPE=host (default) | self (this checkout only) | off).
reap_stragglers() {
  local scope=(--root "$ROOT")
  case "${SHOT_REAP_SCOPE:-host}" in
    off) return 0 ;;
    self) ;;
    host) scope=() ;;
    *) die "SHOT_REAP_SCOPE must be host|self|off" ;;
  esac
  reap_py "${scope[@]}" --skip-state "$STATE" --make-room "${SHOT_SLOTS:-1}" {STACK_LOCK_FD}>&- {SLOT_FD}>&- || log "WARN: reaper failed"
}

proc_starttime() { sed 's/^.*) //' "/proc/$1/stat" 2>/dev/null | awk '{print $20}'; }

# The caller chain the watchdog watches (parent + grandparent, e.g. make + the agent shell); SHOT_DETACHED=1 watches none.
caller_pids() {
  local p="$PPID" i st
  [ "${SHOT_DETACHED:-0}" = 1 ] && return 0
  for i in 1 2; do
    [ -n "$p" ] && [ "$p" -gt 1 ] || return 0
    st="$(proc_starttime "$p")"
    [ -n "$st" ] && printf '%s:%s ' "$p" "$st"
    p="$(awk '{print $4}' "/proc/$p/stat" 2>/dev/null)"
  done
}

# run_begin: everything a capture / anim-shot run does before touching the stack. Ownership = the stack lock (held by this
# process and its watchdog); the slot bounds the number of runs host-wide.
run_begin() {
  trap cleanup EXIT
  trap 'exit 130' INT TERM HUP
  acquire_stack_lock "$STATE/stack.lock"   # one run at a time per stack (it is shared by all skills filmed from this checkout)
  acquire_slot                             # host-wide vfx-shot slot (not the e2e flock)
  RUN_STARTED=1
  PHASE_LOG="$STATE/phases.log"; : > "$PHASE_LOG"; PHASE_T0=$SECONDS
  rm -f "$STATE/run.done" "$STATE/abort"
  # this stack's own leftovers: reuse them only when a previous SHOT_KEEP=1 run kept them and they are within the TTL
  local ku
  ku="$(cat "$STATE/keep-until" 2>/dev/null || true)"
  if [ -n "$ku" ] && [ "$(date +%s)" -lt "${ku%.*}" ]; then
    log "reusing the kept stack $PROJECT (kept for another $(( ${ku%.*} - $(date +%s) ))s)"
  else
    reap_py --only-state "$STATE" --force --lock-held {STACK_LOCK_FD}>&- {SLOT_FD}>&- || true
  fi
  # SHOT_KEEP=1 / SHOT_PIN=1: other checkouts' runs never evict this stack (only its own TTL ends it). Kept stacks of every
  # author are therefore safe from a neighbour's --make-room sweep. Never `rm -rf` an OUT_ROOT that holds a live stack (its .state goes with it).
  if [ "${SHOT_PIN:-0}" = 1 ] || [ "${SHOT_KEEP:-0}" = 1 ]; then : > "$STATE/pinned"; else rm -f "$STATE/pinned"; fi
  : > "$STATE/capture.active"              # tells a parker (kept stack) to keep the clients running, before keep-until goes
  rm -f "$STATE/keep-until"
  echo "$PROJECT" > "$STATE/project"
  mkdir -p "$SHOT_REGISTRY" && echo "$STATE" > "$SHOT_REGISTRY/$PROJECT"
  # shellcheck disable=SC2046
  setsid bash "$HERE/watchdog.sh" "$$" "$STATE" "$SHOT_TIMEOUT" $(caller_pids) </dev/null >/dev/null 2>&1 &
  WATCHDOG_PID=$!
  printf 'PID=%s\nSTART=%s\nWATCHDOG=%s\nKEEP=%s\nTIMEOUT=%s\nPROJECT=%s\nROOT=%s\n' \
    "$$" "$(date +%s)" "$WATCHDOG_PID" "$KEEP" "$SHOT_TIMEOUT" "$PROJECT" "$ROOT" > "$STATE/owner"
  reap_stragglers
  log "run limits: SHOT_TIMEOUT=${SHOT_TIMEOUT}s, SHOT_KEEP=$KEEP$([ "$KEEP" = 1 ] && echo " (TTL ${SHOT_KEEP_TTL}s)"), slots ${SHOT_SLOTS:-1}"
}

if [ "$SOURCED" != 1 ] && [ "$CMD" = down ]; then
  # wait up to 10 min for a capture of this checkout to finish, then remove its stack, kept or not
  reap_py --only-state "$STATE" --force --wait-lock 600 || exit 1
  log "stack $PROJECT removed"
  exit 0
fi
if [ "$SOURCED" != 1 ] && [ "$CMD" = reap ]; then
  # sweep stragglers of every checkout (SHOT_REAP_SCOPE=self: this one only; SHOT_REAP_DRY=1: only print)
  if [ "${SHOT_REAP_SCOPE:-host}" = self ]; then reap_py --root "$ROOT"; else reap_py; fi
  exit 0
fi

# ---------------------------------------------------------------- capture config
VIEW="${VIEW:-all}"
case "$CMD" in
  capture) [ "$SOURCED" = 1 ] || [ -n "$SKILL" ] || die "SKILL=<skill-id> is required (e.g. SKILL=couture-rapier-s2)" ;;
  up|status) ;;
  *) die "usage: vfx-shot.sh [capture|up|down|status|reap]" ;;
esac
if [ "$CMD" = up ] && [ "$VIEW" = all ]; then VIEW=$([ -n "$CASE" ] && echo compare || echo side); fi
TICKS_SET="${TICKS:-}"
TICKS="${TICKS:-60}"                 # ticks filmed after the cast (compare: the case duration_ticks)
export SLOW_RATE="${SHOT_SLOW_RATE:-5}"      # STEPPED=1: server tick rate while filming (ticks/s, 1-20); every tick then stays on screen 1/rate s
STEPPED="${STEPPED:-$([ "${FPS:-20}" = 20 ] && echo 1 || echo 0)}"   # 1 = slow motion (/tick rate SHOT_SLOW_RATE): world, plugin scheduler and client on one clock, several frames per tick, the last is kept (default for 20 fps sheets); 0 = realtime
# Realtime filming skips ticks by construction (the software client draws ~10 fps): opt-in only (SHOT_REALTIME=1), never an accident of STEPPED=0 / FPS!=20.
if [ "$SOURCED" != 1 ] && [ "$STEPPED" != 1 ] && [ "${SHOT_REALTIME:-0}" != 1 ]; then
  echo "vfx-shot: STEPPED=0 / FPS!=20 films in realtime, which drops ticks (marked invalid, never publishable). Set SHOT_REALTIME=1 to do it on purpose." >&2; exit 2
fi
FPS="${FPS:-20}"                     # output rate; FPS=60 keeps 3 frames per tick (frame_NNN = NNN/3 ticks), no sheet/gif
RES="${RES:-}"                       # viewer resolution (client window + framebuffer, GUI scale fixed); default 1920x1080 = the reference's native size
[ "$SOURCED" = 1 ] && RES="${RES:-1280x720}"   # anim-shot keeps the old default
GUI_SCALE="${GUI_SCALE:-1}"          # 1 = the smallest stamp rectangle to mask out of the saved frames
# Client FOV in degrees (vertical, same as the in-game slider). Default 90 for caster/opponent/side: what most players
# run; vanilla 70 reads narrow in a 16:9 viewer. VIEW=compare defaults to the case's intent camera fov (reference
# framing, e.g. 45-50) unless FOV is set; with FOV set, each key's own fov is emulated by dollying (FOV_DOLLY=1, same
# framing as the reference) or ignored (FOV_DOLLY=0: same camera positions, wider view of the scene).
# FOV_OPT (raw normalised options.txt value, 0.0 = 70, +-1 = 110 / 30) stays as a low-level override.
FOV="${FOV:-}"
FOV_DOLLY="${FOV_DOLLY:-1}"
FOV_OPT="${FOV_OPT:-}"
FOV_FROM_ENV=0
[ -n "$FOV" ] && FOV_FROM_ENV=1
RENDER_DISTANCE="${RENDER_DISTANCE:-6}"
TIME_OF_DAY="${TIME_OF_DAY:-6000}"   # `time set`; 6000 = noon (target + floor readable), 13000 = dusk (glow pops, husk goes dark)
# caster -> target, blocks. Default: the enemy distance of the skill's skill-compare case (data/<case>/intent.json: enemy x minus
# caster x at t0), so every review view stages the same fight as the ref; 4 when the skill has no case.
if [ -z "${DISTANCE:-}" ] && [ -n "$SKILL" ]; then
  DISTANCE="$(python3 -I -c '
import json, glob, os, sys
skill, root = sys.argv[1], sys.argv[2]
for cj in sorted(glob.glob(os.path.join(root, "tools/skill-compare/data/*/case.json"))):
    try:
        c = json.load(open(cj))
        if (c.get("skill") or {}).get("asteraSkillId") != skill: continue
        d = json.load(open(os.path.join(os.path.dirname(cj), c.get("intent", {}).get("file", "intent.json"))))
        x = {a["id"]: a["keys"][0]["pos"][0] for a in d["actors"] if a.get("id") in ("caster", "enemy")}
        print(round(x["enemy"] - x["caster"], 2)); break
    except Exception:
        continue
' "$SKILL" "$ROOT" 2>/dev/null)"
fi
DISTANCE="${DISTANCE:-4}"
JOIN_SETTLE="${JOIN_SETTLE:-8}"      # seconds after a client joined before the scene is placed (lobby relocations)
SETTLE="${SETTLE:-5}"                # seconds after the teleport before filming (chunks, join titles)
SETTLE_WARM="${SETTLE_WARM:-2}"      # same, when both clients were already parked in the arena
PRE_ROLL="${PRE_ROLL:-1.0}"          # seconds filmed before the cast (not in the frames)
SHEET_STEP="${SHEET_STEP:-2}"        # contact sheet shows every Nth tick
SHEET_COLS="${SHEET_COLS:-6}"
ITEM="${ITEM:-}"                     # item to hold; default: skill id minus -sN / -def
BEAT_TICKS="${BEAT_TICKS:-}"         # comma list of ticks after the cast (or `case`: the beat ticks of the case): save only those PNGs
KEEP="${SHOT_KEEP:-0}"               # 1 = keep the stack + parked clients for SHOT_KEEP_TTL s after the run (vfx-shot-up always keeps)
[ "$CMD" = up ] && KEEP=1
[ "$SOURCED" = 1 ] && KEEP=0         # anim-shot: its stack is per animation, never worth keeping

CAMERA="${CAMERA:-case}"             # VIEW=compare: case = intent.json camera keys, side = fixed comparison camera, <name> = intent camera_presets.<name>
PRE_TICKS=0                          # frames filmed before the cast (compare only; cast_tick_ms = PRE_TICKS * 50)
ARENA_R=15                           # floor half-size around the arena centre
ARENA_H=12                           # air cleared above the floor
CMP_FOV_OPT=""
CMP_FOV_DEG=""
CAM0_CMD=""
# DUMMIES=<1..3>: husks in the scene (default 1; chain-lightning 3). Extras stand beyond the first (within 4.5 of each other), never toward the caster.
case "$SKILL" in chain-lightning) DUMMIES="${DUMMIES:-3}" ;; esac
DUMMIES="${DUMMIES:-1}"
[[ "$DUMMIES" =~ ^[1-3]$ ]] || die "DUMMIES must be 1, 2 or 3"
DUMMY_EXTRA_FRAME=0; [ "$DUMMIES" -gt 1 ] && DUMMY_EXTRA_FRAME=6   # side/right framing widened by this many blocks of forward extent
ENEMY_X="${SHOT_ENEMY_X:-4.2}"            # compare: dummy distance ahead of the caster
ENEMY_Z="${SHOT_ENEMY_Z:-0}"              # compare: lateral offset of the dummy (miss-case captures: SHOT_ENEMY_Z=6)
for _v in ${VIEW//,/ }; do   # VIEW=top,side films several orbit views in one run (one scene setup, one stack sync)
  case "$_v" in caster|opponent|side|front|back|right|high|near|top|compare|all) ;; *) die "VIEW must be caster|opponent|side|front|back|right|high|near|top|compare|all (comma list allowed, compare alone)";; esac
done
[ "$VIEW" = "${VIEW//,/}" ] || [[ "$VIEW" != *compare* && "$VIEW" != *all* ]] || die "VIEW=compare / all cannot be combined in a list"
[[ "$CAMERA" =~ ^[a-z0-9][a-z0-9_-]{0,31}$ ]] || die "CAMERA must be case|side|<intent camera_presets name>|<tools/vfx-shot/camera-presets.json name>"
if [ "$VIEW" = compare ]; then
  [ -n "$CASE" ] || die "VIEW=compare needs CASE=<case-id> (e.g. CASE=zwei-s3)"
  INTENT="$(python3 -c 'import json,pathlib,sys; d=pathlib.Path(sys.argv[1]); c=json.loads((d/"case.json").read_text()); p=(d/c.get("intent",{}).get("file","intent.json")).resolve(); p.relative_to(d.resolve()); print(p)' "$ROOT/tools/skill-compare/data/$CASE")"
  [ -f "$INTENT" ] || { log "WARN: $INTENT missing, using defaults"; INTENT=-; }
  PRE_TICKS="${PRE_TICKS_CMP:-6}"
  ARENA_R=30
  ARENA_H=7
  FLOOR_BLOCK="${FLOOR_BLOCK:-minecraft:light_gray_concrete}"
fi
if [ -n "$FOV" ]; then
  python3 -c 'import sys; v=float(sys.argv[1]); sys.exit(0 if 30<=v<=110 else 1)' "$FOV" || die "FOV must be a number of degrees between 30 and 110"
fi
if [ -z "$FOV_OPT" ] && [ -z "$FOV" ] && [ "$VIEW" != compare ]; then
  FOV=90
fi
if [ -n "$FOV" ] && [ -z "$FOV_OPT" ]; then
  FOV_OPT="$(python3 -c 'import sys; print("%.4f" % ((float(sys.argv[1]) - 70.0) / 40.0))' "$FOV")"
fi
# Held item = the item that binds the skill: content/items/*.yaml `skill_id: <skill>` (greatsword-cleave -> greatsword,
# rifle-shot -> rifle), else the skill yaml's own `item:`. Never guessed from the skill id: an unbound skill needs an
# explicit ITEM=<item-id> (or ITEM=none for a skill cast with nothing in hand), and a capture without one fails loudly.
if [ -z "$ITEM" ] && [ -n "$SKILL" ]; then
  ITEM="$(grep -lE "^[[:space:]]*-?[[:space:]]*skill_id:[[:space:]]*${SKILL%%@*}[[:space:]]*$" "$ROOT"/content/items/*.yaml 2>/dev/null | head -1 | xargs -r basename -s .yaml)"
  [ -n "$ITEM" ] || ITEM="$(sed -nE 's/^item:[[:space:]]*([A-Za-z0-9_.-]+).*/\1/p' "$ROOT/content/skills/${SKILL%%@*}/skill.yaml" 2>/dev/null | head -1)"
fi
# A callee-only skill (non-empty top-level `frames_in:` or a top-level param with `required: true`) cannot be cast alone: the engine rejects
# the cast (CastError.CalleeOnly) and nothing is ever drawn. Refuse here and name the callers (`use: skill:<id>` in content/skills).
if [ "$CMD" = capture ] && [ -n "$SKILL" ] && [ "$SOURCED" != 1 ]; then
  _base="${SKILL%%@*}"; _yaml="$ROOT/content/skills/$_base/skill.yaml"
  if [ -f "$_yaml" ] && { grep -qE '^frames_in:[[:space:]]*\{[[:space:]]*[^[:space:]}]' "$_yaml" \
      || awk '/^params:/ {p=1; next} /^[^[:space:]#]/ {p=0} p && /required:[[:space:]]*true/ {f=1} END {exit !f}' "$_yaml"; }; then
    _callers="$(grep -rlE "use:[[:space:]]*skill:${_base}([^A-Za-z0-9_-]|\$)" "$ROOT/content/skills" --include='*.yaml' 2>/dev/null \
      | sed -E 's#.*/content/skills/([^/]+)/.*#\1#' | grep -vx -- "$_base" | sort -u | paste -sd, -)"
    die "callee-only: capture the caller ${_callers:-<none found>} (skill '$_base' declares frames_in / required params and is only called with 'use: skill:$_base')"
  fi
fi
[ "$CMD" != capture ] || [ -z "$SKILL" ] || [ -n "$ITEM" ] || die "no item binds skill '$SKILL' (content/items/*.yaml skill_id); set ITEM=<item-id> or ITEM=none"

CACHE="${SHOT_CACHE:-$HOME/.cache/astera-vfx-shot}"
VENV="$CACHE/venv"
MCDIR="$CACHE/mcdir"
MC_VERSION="${MC_VERSION:-26.3}"
PMC_VERSION="${PMC_VERSION:-5.0.6}"
PLUGINS=(core items combat match stats economy persistence)

# ---------------------------------------------------------------- cleanup
# EXIT trap of a run (any exit path: success, die, INT/TERM/HUP). Before the run holds its locks it must not touch the stack
# (another run of this checkout may own it). SHOT_KEEP=1 parks the clients for SHOT_KEEP_TTL s; otherwise (and always after a
# watchdog abort) every process carrying this stack's marker (client process groups: portablemc + java + what they spawned,
# Xvfb, parker) is killed and the compose project removed. run.done tells the watchdog the teardown is complete.
cleanup() {
  [ "$RUN_STARTED" = 1 ] || return 0
  trap '' INT TERM HUP   # a second Ctrl-C must not cut the teardown short
  [ -n "$FILM_PID" ] && kill "$FILM_PID" 2>/dev/null
  if [ -e "$STATE/abort" ]; then KEEP=0; log "stopped by the watchdog: $(grep -E 'stopping run' "$STATE/watchdog.log" | tail -1)"; fi
  if [ "$KEEP" = 1 ]; then
    echo "$(( $(date +%s) + SHOT_KEEP_TTL ))" > "$STATE/keep-until"
    rm -f "$STATE/capture.active"
    park_start
    log "stack $PROJECT kept (SHOT_KEEP=1) until $(date -d "@$(cat "$STATE/keep-until")" '+%H:%M:%S'); make vfx-shot-down drops it now"
  else
    rm -f "$STATE/capture.active" "$STATE/parker.on"
    reap_py --only-state "$STATE" --force --lock-held {STACK_LOCK_FD}>&- {SLOT_FD}>&- || true
    log "stack $PROJECT and its clients removed"
  fi
  : > "$STATE/run.done"
}

# ---------------------------------------------------------------- deployed-state record (provenance)
# $STATE/deployed.json: what the running stack actually serves, written by the sync steps below.
dget() { jq -r --arg k "$1" '.[$k] // ""' "$STATE/deployed.json" 2>/dev/null || true; }
dset() { # key value [key value ...]
  local f="$STATE/deployed.json" k v tmp
  [ -s "$f" ] || echo '{}' > "$f"
  while [ $# -ge 2 ]; do
    k="$1"; v="$2"; shift 2
    tmp="$(jq --arg k "$k" --arg v "$v" '.[$k] = $v' "$f")" && printf '%s\n' "$tmp" > "$f"
  done
}

IN_CONTENT="" IN_PACK="" IN_CODE=""
read_inputs() {
  local js
  js="$(python3 -I "$HERE/shotstate.py" inputs "$ROOT")" || die "could not fingerprint the working tree"
  IN_CONTENT="$(jq -r .contentSourceHash <<<"$js")"
  IN_PACK="$(jq -r .packHash <<<"$js")"
  IN_CODE="$(jq -r .codeHash <<<"$js")"
}

# ---------------------------------------------------------------- stack: artifacts
stage_content() { # stages content/ into $STATE/content-stage with the E2E_FAST tuning the e2e image has
  python3 -I "$HERE/shotstate.py" stage "$ROOT" "$STATE/content-stage" >/dev/null || die "could not stage content/"
  sh "$ROOT/docker/e2e-tune-content.sh" "$STATE/content-stage" || die "e2e content tuning failed (content drifted from docker/e2e-tune-content.sh)"
  STAGE_HASH="$(tree_hash "$STATE/content-stage")"
}

JARS=()
collect_jars() {
  local p
  JARS=()
  for p in "${PLUGINS[@]}"; do
    JARS+=("$(ls "$ROOT"/plugin/"$p"/build/libs/astera-*.jar 2>/dev/null | head -n1)")
  done
  for p in "${JARS[@]}"; do [ -f "$p" ] || return 1; done
}

# One host gradle call for whatever is stale (shadow jars and/or the resource pack; compile-only, a warm daemon makes it seconds).
build_artifacts() { # want_jars want_pack
  local tasks=() p
  [ "$1" = 1 ] && for p in "${PLUGINS[@]}"; do tasks+=(":plugin:$p:shadowJar"); done
  [ "$2" = 1 ] && tasks+=(":tools:resource-pack:buildResourcePack")
  [ "${#tasks[@]}" -gt 0 ] || return 0
  phase "gradle ${tasks[*]##*:}"
  (cd "$ROOT" && ./gradlew -q "${tasks[@]}" >"$STATE/gradle.log" 2>&1 {STACK_LOCK_FD}>&- {SLOT_FD}>&-) || { tail -30 "$STATE/gradle.log" >&2; die "gradle build failed"; }
  if [ "$1" = 1 ]; then collect_jars || die "shadow jars missing after the build"; fi
  if [ "$2" = 1 ]; then [ -s "$ROOT/build/resource-pack/astera.zip" ] || die "resource pack missing after the build"; fi
}

container_content_hash() {
  docker exec "$SERVER_CT" sh -c 'cd /content && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -c1-16' 2>/dev/null
}

push_content() { # stage -> container /content, then reload in place (dev-deploy.sh content)
  local out
  docker exec -u root "$SERVER_CT" rm -rf /content.new || true
  docker cp "$STATE/content-stage/." "$SERVER_CT:/content.new" || die "docker cp content failed"
  # /content is a volume mount point (cannot be renamed): empty it and copy over.
  docker exec -u root "$SERVER_CT" sh -c 'find /content -mindepth 1 -delete && cp -a /content.new/. /content/ && rm -rf /content.new' \
    || die "content swap failed"
}

reload_content() {
  local out
  out="$(rcon "astera admin reload all")"
  log "reload: ${out:-<no output>}"
  case "$out" in *ailed*|*rror*) log "WARN: content reload reported a problem" ;; esac
  out="$(rcon "astera items refresh")"
  log "held items: ${out:-<no output>}"
}

sync_content() {
  local have
  stage_content
  have="$(container_content_hash)"
  if [ "$IN_CONTENT" = "$(dget contentSourceHash)" ] && [ "$have" = "$STAGE_HASH" ]; then
    phase "content: up to date ($STAGE_HASH)"
  else
    phase "content: pushing (deployed ${have:-none}, want $STAGE_HASH)"
    push_content
    reload_content
    have="$(container_content_hash)"
  fi
  [ "$have" = "$STAGE_HASH" ] || die "content hash mismatch after the push: container $have, staged $STAGE_HASH"
  dset contentSourceHash "$IN_CONTENT" deployedContentHash "$have" contentVerified true
}

push_pack() {
  local sha
  docker cp "$ROOT/build/resource-pack/astera.zip" "$PACK_CT:/usr/share/nginx/html/astera.zip" || die "docker cp pack failed"
  docker cp "$ROOT/build/resource-pack/astera.zip.sha1" "$PACK_CT:/usr/share/nginx/html/astera.zip.sha1" || die "docker cp pack sha1 failed"
  verify_pack
}

verify_pack() { # the sha1 the pack host serves must be the one just built (a created, not yet started container cannot be asked)
  local sha want
  want="$(awk '{print $1}' "$ROOT/build/resource-pack/astera.zip.sha1")"
  [ "$(docker inspect -f '{{.State.Running}}' "$PACK_CT" 2>/dev/null)" = true ] || return 0
  sha="$(docker exec "$PACK_CT" cat /usr/share/nginx/html/astera.zip.sha1 | awk '{print $1}')"
  [ "$sha" = "$want" ] || die "pack sha1 mismatch after the copy (host $want, served $sha)"
  dset deployedPackSha1 "$sha"
}

wait_rcon() { # seconds
  local i
  for i in $(seq 1 "$(($1 / 2))"); do
    rcon list >/dev/null 2>&1 && return 0
    sleep 2
  done
  die "RCON never came up ($SERVER_IP:$RCON_PORT); docker logs $SERVER_CT"
}

install_stamp() {
  # the datapack renders the server game time into a boss bar title on every tick: the per-frame tick stamp (framestamp.py)
  local want have
  want="$(tree_hash "$HERE/stamp-pack")"
  have="$(docker exec "$SERVER_CT" sh -c 'cd /data/world/datapacks/vfxstamp 2>/dev/null && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -c1-16' 2>/dev/null)"
  if [ "$have" = "$want" ] && rcon "datapack list" 2>/dev/null | grep -q 'file/vfxstamp'; then return 0; fi
  phase "stamp datapack: installing"
  docker exec -u root "$SERVER_CT" rm -rf /data/world/datapacks/vfxstamp /data/world/datapacks/vfxclap
  docker cp "$HERE/stamp-pack" "$SERVER_CT:/data/world/datapacks/vfxstamp" || die "docker cp stamp-pack failed"
  docker exec -u root "$SERVER_CT" chown -R 1000:1000 /data/world/datapacks/vfxstamp 2>/dev/null || true
  rcon "minecraft:reload" >/dev/null
  sleep 2
  rcon "datapack list" | grep -q vfxstamp || log "WARN: stamp datapack not listed after reload ($(rcon 'datapack list' | head -c 300))"
  rcon "datapack enable \"file/vfxstamp\"" >/dev/null 2>&1 || true
}

restart_server_with_jars() { # jars...
  local jar
  phase "plugins: restarting the server with ${#@} jar(s)"
  compose stop -t 60 e2e >/dev/null 2>&1
  for jar in "$@"; do
    docker cp "$jar" "$SERVER_CT:/data/plugins/$(basename "$jar")" 2>/dev/null || true
    docker cp "$jar" "$SERVER_CT:/plugins/$(basename "$jar")" || die "docker cp jar failed"
  done
  compose start e2e >/dev/null 2>&1 || die "server did not start"
  wait_rcon 240
}

sync_plugins() { # returns 0; sets SERVER_RESTARTED=1 when the server was restarted
  local remote jar changed=()
  collect_jars || return 0
  remote="$(docker exec "$SERVER_CT" sh -c 'sha256sum /plugins/astera-*.jar 2>/dev/null' | tr -d '\r')"
  for jar in "${JARS[@]}"; do
    grep -q "^$(sha256sum "$jar" | cut -d' ' -f1)  /plugins/$(basename "$jar")\$" <<<"$remote" || changed+=("$jar")
  done
  if [ "${#changed[@]}" = 0 ]; then phase "plugins: jars up to date"; else
    restart_server_with_jars "${changed[@]}"
    SERVER_RESTARTED=1
  fi
  dset codeHash "$IN_CODE" deployedJars "$(cat "${JARS[@]}" | sha256sum | cut -c1-16)"
}

CODE_REV="" CODE_DIRTY=""
record_code_revision() {
  CODE_REV="$(git -C "$ROOT" rev-parse HEAD)"
  CODE_DIRTY="$([ -n "$(git -C "$ROOT" status --porcelain -- . ':(exclude)content')" ] && echo true || echo false)"
  dset codeRevision "$CODE_REV" codeDirty "$CODE_DIRTY" deployedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}

SERVER_RESTARTED=0
PACK_PUSHED=0
create_network() {
  SUBNET="$(pick_subnet_prefix)" || { log "no free docker subnet 10.N.77.0/24 (N=211..250)"; return 1; }
  echo "$SUBNET" > "$STATE/subnet"
  compose up --no-build --no-start e2e e2e-resource-pack e2e-redis > "$STATE/compose-create.log" 2>&1 \
    || { tail -30 "$STATE/compose-create.log" >&2; return 1; }
}

create_stack() {
  phase "creating the stack (project $PROJECT)"
  memory_guard "starting the server"
  read_inputs
  build_artifacts 1 1
  stage_content
  SUBNET="10.211.77"   # placeholder for the compose interpolation of a create; create_network picks the real one
  with_global_lock subnet create_network || die "could not create the compose network"
  SUBNET="$(cat "$STATE/subnet")"; SERVER_IP="$SUBNET.2"
  phase "copying jars, content and pack into the containers"
  for jar in "${JARS[@]}"; do docker cp "$jar" "$SERVER_CT:/plugins/$(basename "$jar")" || die "docker cp jar failed"; done
  docker cp "$STATE/content-stage/." "$SERVER_CT:/content" || die "docker cp content failed"
  push_pack
  phase "starting the server"
  compose up -d --no-build --wait e2e e2e-resource-pack e2e-redis > "$STATE/compose-up.log" 2>&1 \
    || { tail -30 "$STATE/compose-up.log" >&2; die "server failed to start"; }
  docker network connect "$NET" "$SELF_CT" >/dev/null 2>&1 || true
  wait_rcon 240
  : > "$STATE/deployed.json"
  verify_pack
  dset contentSourceHash "$IN_CONTENT" deployedContentHash "$STAGE_HASH" contentVerified true packHash "$IN_PACK" \
    codeHash "$IN_CODE" deployedJars "$(cat "${JARS[@]}" | sha256sum | cut -c1-16)" noFx "${SHOT_NO_FX:-0}"
  record_code_revision
  install_stamp
  [ "$(container_content_hash)" = "$STAGE_HASH" ] || die "content hash mismatch right after the first copy"
}

# Bring the stack up (creating it when missing) and make it serve exactly the working tree.
boot_server() {
  local state
  state="$(docker inspect -f '{{.State.Status}}' "$SERVER_CT" 2>/dev/null || true)"
  SERVER_RESTARTED=0
  PACK_PUSHED=0
  if [ "$state" = created ] || { [ -n "$state" ] && [ ! -s "$STATE/deployed.json" ]; }; then
    log "stack $PROJECT was left half-created: removing it"
    stack_down
    state=""
  fi
  if [ -z "$state" ] || ! load_subnet; then
    SUBNET=""
    create_stack
    SERVER_RESTARTED=1
    STACK_WAS_COLD=1
  else
    STACK_WAS_COLD=0
    phase "reusing stack $PROJECT (subnet $SUBNET.0/24, server $state)"
    if [ "$state" != running ]; then
      memory_guard "starting the server"
      compose up -d --no-build --wait e2e e2e-resource-pack e2e-redis >"$STATE/compose-up.log" 2>&1 || die "stack failed to start"
      SERVER_RESTARTED=1
    fi
    docker network connect "$NET" "$SELF_CT" >/dev/null 2>&1 || true
    wait_rcon 240
    read_inputs
    local want_jars=0 want_pack=0
    [ "$IN_CODE" != "$(dget codeHash)" ] && want_jars=1
    [ "$IN_PACK" != "$(dget packHash)" ] && want_pack=1
    [ "$(dget noFx)" != "${SHOT_NO_FX:-0}" ] && { phase "SHOT_NO_FX changed: recreating the server container"; compose up -d --no-build --wait e2e >"$STATE/compose-up.log" 2>&1 || die "recreate failed"; wait_rcon 240; SERVER_RESTARTED=1; }
    build_artifacts "$want_jars" "$want_pack"
    if [ "$want_pack" = 1 ]; then
      phase "pack: pushing"
      push_pack
      PACK_PUSHED=1
    fi
    if [ "$want_jars" = 1 ]; then sync_plugins; else collect_jars && phase "plugins: unchanged"; fi
    install_stamp
    sync_content
    if [ "$PACK_PUSHED" = 1 ]; then
      out="$(rcon "astera admin pack reload force")"
      log "pack reload: ${out:-<no output>}"
    fi
    dset packHash "$IN_PACK" codeHash "$IN_CODE" noFx "${SHOT_NO_FX:-0}"
    record_code_revision
  fi
  SERVER_IP="$SUBNET.2"
  phase "stack ready (content $(dget deployedContentHash), code $(dget codeRevision | cut -c1-8)$([ "$(dget codeDirty)" = true ] && echo +dirty))"
}

# ---------------------------------------------------------------- client
ensure_client_install() {
  # a venv made by another python (e.g. the runner image changed) keeps bin/portablemc but cannot import it
  if [ ! -x "$VENV/bin/portablemc" ] || ! "$VENV/bin/python" -c 'import portablemc' 2>/dev/null; then
    log "installing portablemc $PMC_VERSION into $VENV"
    mkdir -p "$CACHE"
    rm -rf "$VENV"
    python3 -m venv "$VENV" && "$VENV/bin/pip" install -q "portablemc==$PMC_VERSION" \
      || die "could not install portablemc"
  fi
  mkdir -p "$MCDIR"
}

write_client_files() { # name width height
  local name="$1" w="$2" h="$3" wd="$CLIENTS/$1"
  mkdir -p "$wd/resourcepacks"
  rm -f "$wd/options.txt"
  cat > "$wd/options.txt" <<EOF
version:5023
fov:${FOV_OPT:-0.0}
fovEffectScale:${FOV_EFFECT_SCALE:-1.0}
bobView:false
renderDistance:$RENDER_DISTANCE
simulationDistance:6
particles:0
guiScale:$GUI_SCALE
chatVisibility:2
pauseOnLostFocus:false
maxFps:${SHOT_MAX_FPS:-60}
enableVsync:false
soundCategory_master:${SHOT_SOUND_VOLUME:-0.0}
tutorialStep:none
onboardAccessibility:false
skipMultiplayerWarning:true
joinedFirstServer:true
renderClouds:"false"
resourcePacks:["vanilla","file/astera-shot-fix.zip"]
EOF
  if [ -n "${SHOT_PULSE_PREFIX:-}" ]; then
    # OpenAL enumerates devices by description; identical "Null Output" names can select the first sink despite PULSE_SINK.
    printf 'soundDevice:"%s_%s"\n' "$SHOT_PULSE_PREFIX" "$name" >> "$wd/options.txt"
  fi
  # pre-accept the server resource pack (Quick Play looks the address up in servers.dat)
  python3 -I "$HERE/servers_dat.py" "$wd/servers.dat" "$SERVER_IP:25565"
  # Astera's pack stores item textures under textures/generated/items + textures/items, which no vanilla
  # atlas source covers -> every Astera item renders magenta. This overlay adds the missing atlas sources.
  python3 -I "$HERE/mkzip.py" "$HERE/overlay-pack" "$wd/resourcepacks/astera-shot-fix.zip"
}

client_sig() { echo "$1x$2 fov=${FOV_OPT:-0.0} rd=$RENDER_DISTANCE gui=$GUI_SCALE ip=$SERVER_IP sound=${SHOT_SOUND_VOLUME:-0.0} fps=${SHOT_MAX_FPS:-60} pulse=${SHOT_PULSE_PREFIX:-}"; }

save_client() { # name sig
  printf 'CPID=%s\nXPID=%s\nDISPLAY_NUM=%s\nSIG=%s\n' "${CPID[$1]}" "${XPID[$1]}" "${DISP[$1]}" "$2" > "$STATE/clients/$1.env"
}

load_client() { # name -> 0 when a recorded client process is alive (arrays filled)
  local f="$STATE/clients/$1.env"
  [ -f "$f" ] || return 1
  CPID[$1]="$(sed -n 's/^CPID=//p' "$f")"; XPID[$1]="$(sed -n 's/^XPID=//p' "$f")"; DISP[$1]="$(sed -n 's/^DISPLAY_NUM=//p' "$f")"
  kill -0 "${CPID[$1]}" 2>/dev/null && kill -0 "${XPID[$1]}" 2>/dev/null
}

start_client() { # name width height
  local name="$1" w="$2" h="$3" d wd="$CLIENTS/$1" i
  local audio_env=()
  if [ -n "${SHOT_PULSE_PREFIX:-}" ]; then
    audio_env=("PULSE_SERVER=$SHOT_PULSE_SERVER" "PULSE_SINK=${SHOT_PULSE_PREFIX}_$name")
  fi
  memory_guard "starting client $name"
  write_client_files "$name" "$w" "$h"
  # -displayfd: the X server picks a free display number itself and writes it to stdout (no race between runs)
  : > "$wd/display"
  # every client process carries VFX_SHOT_STATE (inherited by java and whatever it spawns): reap.py attributes it to this
  # stack. setsid = own process group (the portablemc pid is the group: portablemc + java die together); no lock fds.
  VFX_SHOT_STATE="$STATE" setsid Xvfb -displayfd 1 -screen 0 "${w}x${h}x24" -nolisten tcp >"$wd/display" 2>"$wd/xvfb.log" {STACK_LOCK_FD}>&- {SLOT_FD}>&- &
  XPID[$name]=$!
  d=""
  for i in $(seq 1 100); do d="$(tr -dc '0-9' <"$wd/display")"; [ -n "$d" ] && break; sleep 0.1; done
  [ -n "$d" ] || die "Xvfb did not report a display (see $wd/xvfb.log)"
  DISP[$name]="$d"
  : > "$wd/client.log"
  # DBUS_SESSION_BUS_ADDRESS: without it the client autolaunches a session dbus + xdg-desktop-portal that escape its group.
  # SHOT_CLIENT_XMX: without -Xmx the JVM may grow to 1/4 of host RAM (2.5-3 GB RSS seen per client).
  VFX_SHOT_STATE="$STATE" DBUS_SESSION_BUS_ADDRESS="disabled:" NO_AT_BRIDGE=1 \
  DISPLAY=":$d" LIBGL_ALWAYS_SOFTWARE=1 LP_NUM_THREADS="${LP_NUM_THREADS:-4}" XDG_RUNTIME_DIR="$wd" \
    setsid env "${audio_env[@]}" "$VENV/bin/portablemc" --main-dir "$MCDIR" start "$MC_VERSION" --mc-dir "$wd" -u "$name" \
    "--jvm-arg=-Xmx${SHOT_CLIENT_XMX:-1536m}" "--jvm-arg=-Xms256m" \
    --join-server "$SERVER_IP" --join-server-port 25565 --resolution "${w}x${h}" \
    >"$wd/client.log" 2>&1 {STACK_LOCK_FD}>&- {SLOT_FD}>&- &
  CPID[$name]=$!
  save_client "$name" "$(client_sig "$w" "$h")"
  log "client $name on :$d ($w x $h)"
}

stop_client() {
  local n="$1"
  [ -n "${CPID[$n]:-}" ] && kill -TERM -- "-${CPID[$n]}" 2>/dev/null
  [ -n "${XPID[$n]:-}" ] && kill "${XPID[$n]}" 2>/dev/null
  sleep 0.5
  [ -n "${CPID[$n]:-}" ] && kill -KILL -- "-${CPID[$n]}" 2>/dev/null
  unset "CPID[$n]" "XPID[$n]" "DISP[$n]"
  rm -f "$STATE/clients/$n.env"
}

# join -> server pack applied (atlas rebuilt after the server/ reload) -> world visible
wait_client_ready() { # name
  local name="$1" wd="$CLIENTS/$1" i
  for i in $(seq 1 120); do
    grep -qw -- "$name" <<<"$(rcon list 2>/dev/null)" && break
    kill -0 "${CPID[$name]}" 2>/dev/null || { tail -20 "$wd/client.log" >&2; die "client $name exited before joining"; }
    sleep 2
  done
  grep -qw -- "$name" <<<"$(rcon list 2>/dev/null)" || { tail -20 "$wd/client.log" >&2; die "client $name never joined"; }
  # the server pack reload names a "server/" pack; wait for it and then for the items atlas it triggers
  for i in $(seq 1 90); do
    grep -q 'Reloading ResourceManager:.*server/' "$wd/client.log" && break
    sleep 1
  done
  grep -q 'Reloading ResourceManager:.*server/' "$wd/client.log" || log "WARN: $name: server resource pack was not applied"
  sleep 4
  if grep -q 'Missing textures in model astera:generated/items/' "$wd/client.log"; then
    log "WARN: $name still reports missing Astera item textures (check the overlay pack)"
  fi
}

pack_reloads() { grep -c 'Reloading ResourceManager:.*server/' "$CLIENTS/$1/client.log" 2>/dev/null || echo 0; }

# Reuse a parked client when it is alive, joined and was started with the same window/options; otherwise (re)start it.
# Sets CLIENT_STARTED=1 when a client had to be started.
ensure_client() { # name width height
  local name="$1" w="$2" h="$3" sig
  sig="$(client_sig "$w" "$h")"
  if load_client "$name" && [ "$(sed -n 's/^SIG=//p' "$STATE/clients/$name.env")" = "$sig" ] \
     && grep -qw -- "$name" <<<"$(rcon list 2>/dev/null)"; then
    log "client $name: parked and joined, reusing (:${DISP[$name]})"
    return 0
  fi
  [ -f "$STATE/clients/$name.env" ] && { log "client $name: not reusable (dead, dropped or other window/FOV), restarting"; stop_client_state "$name"; unset "CPID[$name]" "XPID[$name]" "DISP[$name]"; }
  start_client "$name" "$w" "$h"
  wait_client_ready "$name"
  CLIENT_STARTED=1
}

# After a pack re-push the parked clients re-download and reload the resource manager.
wait_pack_applied() { # name count_before
  local i
  for i in $(seq 1 90); do
    [ "$(pack_reloads "$1")" -gt "$2" ] && { sleep 3; return 0; }
    sleep 1
  done
  log "WARN: $1 did not reload the pushed resource pack within 90 s"
}

key() { DISPLAY=":${DISP[$1]}" xdotool key "${@:2}"; }

# ---------------------------------------------------------------- scene
# The lobby plugin's void rescue (content/worlds/lobby.yaml void_rescue_y = 19) sends any player who moves
# below y=19 back to the lobby, in the overworld too -- so the arena is a stone platform high in the sky.
CX=300.5
CZ=300.5
Y=100
FLOOR_BLOCK="${FLOOR_BLOCK:-minecraft:smooth_stone}"

tp() { # player x y z yaw pitch
  rcon "execute in minecraft:overworld run tp $1 $2 $3 $4 $5 $6" >/dev/null
}

build_arena() {
  local i out
  local lo hi
  lo=$((285 - (ARENA_R - 15))); hi=$((315 + (ARENA_R - 15)))
  rcon "execute in minecraft:overworld run forceload add $((lo - 5)) $((lo - 5)) $((hi + 5)) $((hi + 5))" >/dev/null
  for i in $(seq 1 40); do
    out="$(rcon "execute in minecraft:overworld run fill $lo $((Y - 1)) $lo $hi $((Y - 1)) $hi $FLOOR_BLOCK")"
    [[ "$out" == *"not loaded"* ]] || break
    sleep 1
  done
  [[ "$out" == *"not loaded"* ]] && die "arena chunks never loaded"
  # A main world that is not void (a persisted level from before ADR-0255: generated terrain) roofs the arena with
  # mountains and films it in the dark. Open the sky above the arena, TOP DOWN (so sand / gravel has nothing left to fall
  # from), in slabs that fit the 32768-block fill limit; then drop any falling block and clear the arena itself.
  local side=$((hi - lo + 1)) step y bottom
  step=$((32768 / (side * side))); [ "$step" -ge 1 ] || step=1
  for ((y = 319; y > Y + ARENA_H; y -= step)); do
    bottom=$((y - step + 1)); [ "$bottom" -gt $((Y + ARENA_H)) ] || bottom=$((Y + ARENA_H + 1))
    rcon "execute in minecraft:overworld run fill $lo $bottom $lo $hi $y $hi minecraft:air" >/dev/null
  done
  rcon "kill @e[type=minecraft:falling_block]" >/dev/null
  rcon "execute in minecraft:overworld run fill $lo $Y $lo $hi $((Y + ARENA_H)) $hi minecraft:air" >/dev/null
}

setup_world() {
  rcon "time set $TIME_OF_DAY" >/dev/null
  rcon "weather clear" >/dev/null
  rcon "gamerule advance_time false" >/dev/null
  rcon "gamerule doDaylightCycle false" >/dev/null
  rcon "gamerule advance_weather false" >/dev/null
  rcon "gamerule doWeatherCycle false" >/dev/null
  rcon "gamerule spawn_mobs false" >/dev/null
  rcon "gamerule doMobSpawning false" >/dev/null
  rcon "kill @e[type=minecraft:husk]" >/dev/null
  rcon "kill @e[type=minecraft:item]" >/dev/null
}

# A parked arena carries the previous skill's leftovers: display entities, effects, damage.
reset_arena() {
  local t
  for t in item_display block_display text_display interaction armor_stand marker; do
    rcon "execute in minecraft:overworld positioned $CX $Y $CZ run kill @e[type=minecraft:$t,distance=..70]" >/dev/null
  done
  rcon "effect clear Caster" >/dev/null
  rcon "effect give Caster minecraft:instant_health 1 30 true" >/dev/null
}

# Target dummy: no AI behaviour (does not wander / hunt / hit / talk) but still takes knockback and velocity,
# which NoAI:1b would suppress. Rotation is fixed at summon (it only "looks at" the caster, the one
# non-spectator player). HP 1024 (the attribute cap: no skill kills it mid-capture, so the scene never changes), fresh per run (the husk is killed at the start and end of every view).
dummy_nbt() { # yaw
  printf '{Silent:1b,PersistenceRequired:1b,Rotation:[%sf,0f],attributes:[{id:"minecraft:max_health",base:1024},{id:"minecraft:movement_speed",base:0},{id:"minecraft:follow_range",base:0},{id:"minecraft:attack_damage",base:0}],Health:1024f}' "$1"
}

summon_target() {
  local tz
  tz="$(awk -v z="$CZ" -v d="$DISTANCE" 'BEGIN{printf "%.1f", z+d}')"
  rcon "execute in minecraft:overworld run summon minecraft:husk $CX $Y $tz $(dummy_nbt 180)" >/dev/null
  # extra dummies relative to the first: (+1.5 forward, 2 sideways) and (+3.5 forward, 2.5 sideways), on the far side from the side camera (it sits at -x): both beyond the first, never toward the caster, so the aim-line husk is nearest and hit first
  if [ "$DUMMIES" -ge 2 ]; then
    rcon "execute in minecraft:overworld run summon minecraft:husk $(awk -v x="$CX" 'BEGIN{printf "%.2f", x+2}') $Y $(awk -v z="$tz" 'BEGIN{printf "%.2f", z+1.5}') $(dummy_nbt 180)" >/dev/null
  fi
  if [ "$DUMMIES" -ge 3 ]; then
    rcon "execute in minecraft:overworld run summon minecraft:husk $(awk -v x="$CX" 'BEGIN{printf "%.2f", x+2.5}') $Y $(awk -v z="$tz" 'BEGIN{printf "%.2f", z+3.5}') $(dummy_nbt 180)" >/dev/null
  fi
}

target_z() { awk -v z="$CZ" -v d="$DISTANCE" 'BEGIN{printf "%.1f", z+d}'; }

give_item() { # player
  rcon "clear $1" >/dev/null
  [ "$ITEM" = none ] && return 0
  grep -qi "gave" <<<"$(rcon "astera items give $1 $ITEM")" || die "could not give item '$ITEM' to $1 (set ITEM=<item-id>)"
}

# ---------------------------------------------------------------- capture
# film <viewer> <dir>: records the viewer's X display, then framestamp.py reads the tick stamp the client rendered into every
# frame and writes frame_NNN.png (stamp rectangle masked) + <dir>/framestamp.json (frame <-> tick map).
film() { # viewer view_dir
  local viewer="$1" dir="$2" rec="$OUT_ROOT/.rec-$SHOT_ID.mkv" d="${DISP[$1]}"
  local cast_stamp clog="$CLIENTS/$1/client.log" log_off pos_start pos_end fpid i end_wait survival=0
  log_off="$(stat -c %s "$clog" 2>/dev/null || echo 0)"
  rm -f "$rec"
  rcon "tick rate 20" >/dev/null 2>&1 || true; rcon "tick unfreeze" >/dev/null 2>&1 || true   # a killed earlier run may have left the server slow
  DISPLAY=":$d" xdotool mousemove 320 180 2>/dev/null || true
  # showinfo (before the rebase) logs each frame's wall-clock pts -> epoch time of the first frame
  ffmpeg -nostdin -loglevel info -y -copyts -use_wallclock_as_timestamps 1 -f x11grab -framerate "$([ "$STEPPED" = 1 ] && echo "${SHOT_REC_FPS:-15}" || echo "$FPS")" \
    -video_size "$RES_FILM" -i ":$d" -vf "showinfo,setpts=PTS-STARTPTS" \
    -c:v libx264 -preset ultrafast -crf 10 -pix_fmt yuv444p -f matroska "$rec" 2>"$rec.log" &
  fpid=$!
  FILM_PID=$fpid
  for i in $(seq 1 100); do grep -q ' pts:' "$rec.log" 2>/dev/null && break; sleep 0.1; done
  sleep "$PRE_ROLL"
  pos_start="$(rcon "data get entity $viewer Pos" | sed 's/.*data: //') in $(rcon "data get entity $viewer Dimension" | sed 's/.*data: //')"
  log "caster before cast: $(rcon "data get entity Caster Pos" | sed 's/.*data: //') in $(rcon "data get entity Caster Dimension" | sed 's/.*data: //')"
  [ -s "$STATE/camera-plan.tsv" ] && [ "$VIEW_NOW" = compare ] || : > "$STATE/camera-plan.tsv"
  # SHOT_CASTER_TURN="<tick>:<yaw>[,<tick>:<yaw>...]": turns the caster (server-side /rotate, pitch kept (a /tp would eject the rig passengers)) that many ticks after the cast,
  # to check that the skin / rig / VFX follow the live yaw. Works in every view (the plan is just rcon commands by time).
  if [ -n "${SHOT_CASTER_TURN:-}" ]; then
    [[ "$SHOT_CASTER_TURN" =~ ^[0-9]+:-?[0-9.]+(:-?[0-9.]+)?(,[0-9]+:-?[0-9.]+(:-?[0-9.]+)?)*$ ]] || die "SHOT_CASTER_TURN must look like 20:90,40:180:30 (tick:yaw[:pitch])"
    local turn
    for turn in ${SHOT_CASTER_TURN//,/ }; do
      IFS=: read -r t_tick t_yaw t_pitch <<<"$turn"
      printf '%s\trotate Caster %s %s\n' "$(( t_tick * 50 ))" "$t_yaw" "${t_pitch:-~}" >> "$STATE/camera-plan.tsv"
    done
  fi
  # one RCON connection: game time, cast, game time, then (compare) the camera cuts of the plan. Stepped: the server is frozen and
  # advanced one tick at a time until the viewer's screen shows each new tick; realtime: the plan runs on the wall clock.
  local dstep=()
  [ "$STEPPED" = 1 ] && dstep=(DIRECTOR_SLOW_RATE="$SLOW_RATE" DIRECTOR_STEP_PRE="$PRE_TICKS")
  env DIRECTOR_MARK_CMD="time query gametime" "${dstep[@]}" python3 -I "$HERE/director.py" "$SERVER_IP" "$RCON_PORT" "$RCON_PASS" "$STATE/camera-plan.tsv" "astera skill cast $SKILL Caster --force" \
    >"$STATE/director.stdout" 2>"$STATE/director.stderr" || { rcon "tick rate 20" >/dev/null 2>&1; kill "$fpid" 2>/dev/null; cat "$STATE/director.stderr" >&2; die "director failed"; }
  phase "cast + camera plan done"
  cast_stamp="$(awk '/^STAMP/{print $2}' "$STATE/director.stderr")"
  [ -n "$cast_stamp" ] || { kill "$fpid" 2>/dev/null; die "cast command failed"; }
  # film until the motion (plus the pre-ticks of the window) is over
  end_wait="$(awk -v c="$cast_stamp" -v t="$TICKS" -v n="$(date +%s.%N)" -v st="$STEPPED" -v r="$SLOW_RATE" 'BEGIN{w = (st == 1 ? c + t/r + 1.0 - n : c + t/20 + 0.8 - n); printf "%.2f", (w > 0 ? w : 0)}')"
  sleep "$end_wait"
  kill -TERM "$fpid" 2>/dev/null
  wait "$fpid" 2>/dev/null
  FILM_PID=""
  rcon "tick rate 20" >/dev/null 2>&1 || true        # slow-motion filming over: back to the normal tick loop
  pos_end="$(rcon "data get entity $viewer Pos" | sed 's/.*data: //') in $(rcon "data get entity $viewer Dimension" | sed 's/.*data: //')"
  [ -s "$rec" ] || die "recording failed"
  rm -rf "$dir"; mkdir -p "$dir"
  cp "$STATE/director.stderr" "$dir/director-timing.log"
  # viewer session evidence for capture_meta.py: client log written during the recording + position before/after
  tail -c +"$((log_off + 1))" "$clog" > "$dir/viewer-client.log" 2>/dev/null || : > "$dir/viewer-client.log"
  printf '%s\n%s\n' "$pos_start" "$pos_end" > "$dir/viewer-pos.txt"
  local all="$dir"
  if [ -n "$BEAT_TICKS" ]; then all="$dir/.all"; mkdir -p "$all"; fi
  survival=1    # every viewer: the Astera status HUD glyphs (bottom centre) cannot be blanked client-side, they are masked like the hotbar
  if ! python3 -I "$HERE/framestamp.py" "$rec" "$rec.log" "$STATE/director.stderr" "$cast_stamp" "$PRE_TICKS" "$TICKS" "$FPS" "$GUI_SCALE" "$survival" \
      "$all" "$dir/framestamp.json" "$STEPPED" > "$STATE/framestamp.out" 2>"$STATE/framestamp.err"; then
    if [ "${SHOT_KEEP_REC:-0}" = 1 ]; then mv "$rec" "$dir/recording.mkv"; mv "$rec.log" "$dir/recording.showinfo.log"; fi
    die "tick stamp could not be read ($(tail -1 "$STATE/framestamp.err")); SHOT_KEEP_REC=1 keeps the recording"
  fi
  phase "framestamp done"
  # the server's [vfx-trace] lines of this cast (play/spawn/pos): skill-compare derives fx spawn ticks and caster travel from them
  local ct; ct="$(jq -r '.castTick // empty' "$dir/framestamp.json" 2>/dev/null)"
  if [ -n "$ct" ]; then
    docker logs "$SERVER_CT" 2>&1 | grep -E '\[(vfx|skill)-trace\]' | tr -d '\r' \
      | awk -v lo="$((ct - 1))" -v hi="$((ct + TICKS + PRE_TICKS + 10))" '{ if (match($0, /tick=[0-9]+/)) { t = substr($0, RSTART + 5, RLENGTH - 5) + 0; if (t >= lo && t <= hi) print } }' > "$dir/trace.log" || true
  fi
  log "tick stamp: $(cat "$STATE/framestamp.out")"
  # nothing ever drawn after the cast = a silent no-op (rejected cast, wrong skill, dead fx): never a successful capture
  [ "$(jq -r '.firstFxTickAfterCast // "none"' "$dir/framestamp.json" 2>/dev/null)" != none ] \
    || die "no fx was ever drawn after the cast (first fx +None ticks): the cast did nothing; check the server log for [skill-error]"
  if [ "${SHOT_KEEP_REC:-0}" = 1 ]; then mv "$rec" "$dir/recording.mkv"; mv "$rec.log" "$dir/recording.showinfo.log"; else rm -f "$rec" "$rec.log"; fi
  if [ -n "$BEAT_TICKS" ]; then keep_beats "$all" "$dir"; fi
}

# BEAT_TICKS: keep only the PNGs of the beat ticks (frame_NNN keeps its index: NNN = (tick + PRE_TICKS) * FPS / 20).
keep_beats() { # all_dir dir
  local ticks t n=0 idx
  if [ "$BEAT_TICKS" = case ]; then
    ticks="$(jq -r '[.beats[]?.tick] | unique | join(",")' "$ROOT/tools/skill-compare/data/$CASE/case.json")"
  else
    ticks="$BEAT_TICKS"
  fi
  for t in ${ticks//,/ }; do
    idx=$(((t + PRE_TICKS) * FPS / 20))
    if [ -f "$1/$(printf 'frame_%03d.png' "$idx")" ]; then
      mv "$1/$(printf 'frame_%03d.png' "$idx")" "$2/"
      n=$((n + 1))
    else
      log "WARN: beat tick $t (frame $idx) is outside the recorded window"
    fi
  done
  rm -rf "$1"
  BEAT_ONLY=1
  log "BEAT_TICKS: kept $n beat frame(s) of ticks $ticks"
}

meta_py() {
  # VFX_SHOT_SCENE = caster feet x y z, then the target offset (compare: along +x, orbit views: along +z) and the camera mode;
  # VFX_SHOT_CAM_STATIC = the fixed Watcher tp of an orbit view ("x y z yaw pitch"; compare reads camera-plan.tsv instead)
  VFX_SHOT_STATE="$STATE" VFX_SHOT_STACK_COLD="${STACK_WAS_COLD:-0}" \
    VFX_SHOT_BEAT_ONLY="${BEAT_ONLY:-0}" VFX_SHOT_SCENE="$CX $Y $CZ ${SCENE_TARGET_OFFSET:-0} ${SCENE_TARGET_AXIS:-z}" \
    VFX_SHOT_CAM_STATIC="${SCENE_CAM_STATIC:-}" python3 -I "$@"
}

write_meta() { # dir view n  (fps = $FPS; FPS=60 frames are 3 per tick)
  local dir="$1" view="$2" n="$3"
  local fov_mode=fixed
  if [ "$view" = compare ]; then
    if [ "$FOV_FROM_ENV" != 1 ]; then fov_mode=intent-first-key
    elif [ "$FOV_DOLLY" = 0 ]; then fov_mode=override-nodolly
    else fov_mode=override-dolly; fi
    printf '{"view":"compare","case":"%s","camera":"%s","skill":"%s","fps":%d,"cast_tick_ms":%d,"frames":%d,"fov":%s,"fovMode":"%s","resolution":"%s"}\n' \
      "$CASE" "$CAMERA" "$SKILL" "$FPS" "$((PRE_TICKS * 50))" "$n" "${FOV:-70}" "$fov_mode" "$RES_FILM" > "$dir/meta.json"
    SCENE_TARGET_OFFSET="$ENEMY_X" SCENE_TARGET_AXIS=x \
      meta_py "$HERE/capture_meta.py" "$ROOT/tools/skill-compare/data/$CASE/case.json" "$dir" "$INTENT" \
      || die "capture_meta.py failed for view $view"
  else
    printf '{"view":"%s","skill":"%s","fps":%d,"cast_tick_ms":0,"frames":%d,"fov":%s,"fovMode":"%s","resolution":"%s"}\n' \
      "$view" "$SKILL" "$FPS" "$n" "${FOV:-70}" "$fov_mode" "$RES_FILM" > "$dir/meta.json"
    # caster / opponent are first-person views: there is no spectator Watcher camera, so no static camera key (the scene block stays)
    local cam=""
    case "$view" in caster|opponent) ;; *) cam="$(orbit_cam "$view")" || die "orbit_cam failed for view $view" ;; esac
    SCENE_TARGET_OFFSET="$DISTANCE" SCENE_TARGET_AXIS=z SCENE_CAM_STATIC="$cam" \
      meta_py "$HERE/capture_meta.py" - "$dir" || die "capture_meta.py failed for view $view"
  fi
}

make_outputs() { # dir view
  local dir="$1" view="$2" n
  n="$(ls "$dir"/frame_*.png 2>/dev/null | wc -l)"
  [ "$n" -gt 0 ] || die "no frames extracted into $dir"
  if [ "${BEAT_ONLY:-0}" = 1 ]; then
    write_meta "$dir" "$view" "$n"
    log "$view: $n beat frame(s) -> $dir (no sheet/gif/mp4 in BEAT_TICKS mode)"
    return 0
  fi
  # lean (capture farm): only what skill-compare shows (webp frames + meta); full: sheet, gif and mp4 too. The encoders are independent
  # and CPU-bound, so they run side by side.
  local encoders=()
  if [ "${SHOT_OUTPUTS:-full}" != lean ]; then
    local args=() i
    for ((i = 0; i < n; i += SHEET_STEP)); do
      args+=(-label "t+$((i - PRE_TICKS))" "$dir/$(printf 'frame_%03d.png' "$i")")
    done
    ( montage "${args[@]}" -tile "${SHEET_COLS}x" -geometry 320x180+3+3 -background '#151515' -fill white \
        -pointsize 16 "$dir/sheet.png" 2>/dev/null || log "WARN: montage failed" ) &
    encoders+=($!)
    ( ffmpeg -nostdin -loglevel error -y -framerate 20 -i "$dir/frame_%03d.png" \
        -vf "scale=640:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=200[p];[b][p]paletteuse" \
        "$dir/$SKILL-$view.gif" || log "WARN: gif failed" ) &
    encoders+=($!)
    ( ffmpeg -nostdin -loglevel error -y -framerate 20 -i "$dir/frame_%03d.png" -c:v libx264 -preset veryfast -crf 16 \
        -pix_fmt yuv420p "$dir/$SKILL-$view.mp4" || log "WARN: mp4 failed" ) &
    encoders+=($!)
  fi
  ffmpeg -nostdin -loglevel error -y -i "$dir/frame_%03d.png" -c:v libwebp -quality "${SHOT_WEBP_QUALITY:-96}" \
    -compression_level 4 -threads 4 -start_number 0 "$dir/frame_%03d.webp" || log "WARN: web preview failed; serving PNG"
  [ "${#encoders[@]}" -eq 0 ] || wait "${encoders[@]}"   # not a bare `wait`: the watchdog / parker are our children too
  write_meta "$dir" "$view" "$n"
  phase "outputs done ($view, ${SHOT_OUTPUTS:-full})"
  log "$view: $n frames -> $dir"
}

# ---------------------------------------------------------------- views
players_of() { case "$1" in caster) echo Caster ;; opponent) echo "Caster Target" ;; side|front|back|right|high|near|top|compare) echo "Caster Watcher" ;; esac; }

# orbit_cam <view>: "x y z yaw pitch" of the spectator camera for the orbit views, relative to the caster (CX, Y, CZ) facing
# +z with the target DISTANCE ahead. Each camera looks at the caster's chest / swing so the whole blade is in frame; front sits
# 2 blocks beside the target line so the target does not hide the caster; near = a bystander at eye height ~2.4 blocks away.
orbit_cam() {
  FOV="${FOV:-}" python3 -I -c '
import math, sys
v, cx, y, cz, d = sys.argv[1], *map(float, sys.argv[2:])
import os
t = math.tan(math.radians(float(os.environ.get("FOV") or 90)) / 2)  # the client vertical fov
hw = 16 / 9 * t  # half frame width per block of distance
dmin = 1.8 / (2 * t * 0.33)  # caster ~1/3 of the frame height
cams = {  # (camera offset, look-at offset) from the caster feet; x = +east = the caster left, z = forward
    # side/right: close and level like the reference -- the caster is ~1/3 of the frame height (1.8 / (2 D tan(fov/2)), D >= dmin)
    # and the dash (caster -> target, d blocks) stays inside the 16:9 frame (half width 16/9 tan(fov/2) D >= d/2 + 1.5); looks at its midpoint.
    "side": ((-max(dmin, (d / 2 + 1.5) / hw), 0.1, d / 2), (0.0, 1.0, d / 2)),  # the caster RIGHT side: screen-right = forward, same orientation as the skill-compare reference
    "right": ((max(dmin, (d / 2 + 1.5) / hw), 0.1, d / 2), (0.0, 1.0, d / 2)),
    "front": ((2.0, 1.7, d + 3.0), (0.0, 1.2, 0.6)),
    "back": ((-1.0, 2.0, -5.0), (0.0, 1.2, 0.8)),
    "high": ((4.0, 5.8, -1.5), (0.0, 0.9, 1.0)),
    "near": ((2.2, 1.62, 1.2), (0.0, 1.2, 0.3)),
}
if v == "top":  # straight down, yaw 0: caster-forward = image top, caster right = image right (CW/CCW unambiguous)
    print("%.2f %.2f %.2f 0.0 90.0" % (cx, y + 8.0, cz + 1.0))
    raise SystemExit
c, look = cams[v]
if look is None:
    yaw, pitch = (90.0 if c[0] > 0 else -90.0), 8.0
else:
    dx, dy, dz = (look[i] - c[i] - (1.62 if i == 1 else 0.0) for i in range(3))  # c is the tp (feet) position; the eye is 1.62 above
    yaw = math.degrees(math.atan2(-dx, dz))
    pitch = -math.degrees(math.atan2(dy, math.hypot(dx, dz)))
print("%.2f %.2f %.2f %.1f %.1f" % (cx + c[0], y + c[1], cz + c[2], yaw, pitch))
' "$1" "$CX" "$Y" "$CZ" "$(awk -v d="$DISTANCE" -v e="$DUMMY_EXTRA_FRAME" 'BEGIN{print d+e}')"
}


in_arena() { # player -> 0 when in the overworld, x/z in the 300s
  local dim pos
  dim="$(rcon "data get entity $1 Dimension")"
  pos="$(rcon "data get entity $1 Pos")"
  # the arena is centred on CX/CZ (300.5): x/z anywhere in 250..350 counts (a camera behind the caster is at x ~ 296)
  [[ "$dim" == *overworld* ]] && [[ "$pos" =~ \[(-?[0-9]+)\.[0-9]*d,\ [^,]*,\ (-?[0-9]+)\. ]] \
    && (( BASH_REMATCH[1] >= 250 && BASH_REMATCH[1] <= 350 && BASH_REMATCH[2] >= 250 && BASH_REMATCH[2] <= 350 ))
}

# The film camera is a creative player, not a spectator: a spectator renders every invisible entity translucent, and a
# performance hides the caster's body by invisibility (ADR-0276), so the spectator saw a ghost of the standing caster behind the
# rig. Gravity 0 keeps the tp'd camera still between ticks; the hand holds an item whose model is empty (no arm in view); the
# team makes the camera non-solid so a dash through it does not push the caster.
watcher_camera_mode() {
  rcon "team add cmp" >/dev/null 2>&1
  rcon "team modify cmp collisionRule never" >/dev/null
  rcon "team modify cmp nametagVisibility never" >/dev/null
  rcon "team join cmp Watcher" >/dev/null
  rcon "gamemode spectator Watcher"  # out of reach: a spectator is no skill target and takes no knockback (creative was hit and flung by the sweep) >/dev/null
  rcon "attribute Watcher minecraft:gravity base set 0" >/dev/null
  rcon 'item replace entity Watcher weapon.mainhand with minecraft:stick[item_model="astera:shot_empty"]' >/dev/null
  rcon "item replace entity Watcher weapon.offhand with minecraft:air" >/dev/null
}

place_scene() { # view target_z
  local view="$1" tz="$2" who
  rcon "kill @e[type=minecraft:husk]" >/dev/null
  # A client left parked by an earlier view (Target after `opponent`, Watcher after an orbit view) still stands where that
  # view put it: in survival it is a second victim of the skill (a lethal hit sends it to the lobby, it replaces the dummy
  # on screen and it hides / is hit instead of the target). Park every player this view does not use as an invisible
  # spectator far from the arena; an offline name is a harmless rcon error.
  for who in Watcher Caster Target; do
    case " $(players_of "$view") " in *" $who "*) continue ;; esac
    rcon "gamemode spectator $who" >/dev/null 2>&1
    tp "$who" "$CX" "$((Y + 60))" "$((${CZ%.*} - 150))" 0 0 2>/dev/null
  done
  rcon "gamemode survival Caster" >/dev/null
  [ "$view" = compare ] || tp Caster "$CX" "$Y" "$CZ" 0 0
  case "$view" in
    caster)
      summon_target ;;
    opponent)
      # Player HP is Astera's CURRENT_HP/MAX_HP container (vanilla max_health / instant_health do not touch it), so a lethal
      # skill kills a survival Target mid-capture and RespawnOnPlayerKilled sends it to the lobby. The opponent view therefore
      # films from the victim's spot with the Target as an immune spectator, and a husk dummy at the same spot takes the hit.
      summon_target
      rcon "gamemode spectator Target" >/dev/null
      # 1.6 blocks behind the dummy and a little above its head, looking at the caster: inside the husk the screen is its face
      tp Target "$CX" "$(awk -v y="$Y" 'BEGIN{printf "%.2f", y+0.6}')" "$(awk -v z="$tz" 'BEGIN{printf "%.2f", z+1.6}')" 180 5 ;;
    compare)
      # caster faces +x (yaw -90); enemy ENEMY_X ahead looking back; spectator camera from the plan
      rcon "team add cmp" >/dev/null 2>&1
      rcon "team modify cmp nametagVisibility never" >/dev/null
      rcon "team join cmp Caster" >/dev/null
      tp Caster "$CX" "$Y" "$CZ" -90 0
      rcon "execute in minecraft:overworld run summon minecraft:husk $(awk -v x="$CX" -v d="$ENEMY_X" 'BEGIN{printf "%.2f", x+d}') $Y $(awk -v z="$CZ" -v d="$ENEMY_Z" 'BEGIN{printf "%.2f", z+d}') $(dummy_nbt 90)" >/dev/null
      # extra dummies on both sides of the first (caster-right = +z), 3.0 blocks off the line
      for _dz in $(seq 1 $((DUMMIES - 1))); do
        rcon "execute in minecraft:overworld run summon minecraft:husk $(awk -v x="$CX" -v d="$ENEMY_X" 'BEGIN{printf "%.2f", x+d+1}') $Y $(awk -v z="$CZ" -v d="$ENEMY_Z" -v k="$_dz" 'BEGIN{printf "%.2f", z+d+(k==1?3:-3)}') $(dummy_nbt 90)" >/dev/null
      done
      watcher_camera_mode
      rcon "$CAM0_CMD" >/dev/null ;;
    side|front|back|right|high|near|top)
      summon_target
      watcher_camera_mode
      # shellcheck disable=SC2046
      tp Watcher $(orbit_cam "$view") ;;
  esac
}

# prepare_view <view>: plan, clients and scene up to "ready to film". Sets VIEWER, DUMMY_CASTER, RES_FILM.
prepare_view() {
  local view="$1" W H tz res
  tz="$(target_z)"
  VIEW_NOW="$view"
  DUMMY_CASTER=0
  case "$view" in
    caster)   VIEWER="Caster" ;;
    opponent) VIEWER="Target"; DUMMY_CASTER=1 ;;
    side|front|back|right|high|near|top) VIEWER="Watcher"; DUMMY_CASTER=1 ;;
    compare)  VIEWER="Watcher"; DUMMY_CASTER=1 ;;
  esac
  res="${RES:-}"
  if [ -z "$res" ]; then res=1920x1080; fi
  RES_FILM="$res"
  W="${res%x*}"; H="${res#*x}"
  if [ "$view" = compare ]; then
    local dolly=dolly
    [ "$FOV_DOLLY" = 0 ] && dolly=nodolly
    eval "$(python3 -I "$HERE/compare_plan.py" "$INTENT" "$CAMERA" "$CX" "$Y" "$CZ" "$STATE/camera-plan.tsv" "${FOV:--}" "$dolly")"
    FOV_OPT="$CMP_FOV_OPT"
    FOV="$CMP_FOV_DEG"
    [ -n "$TICKS_SET" ] || TICKS="$DURATION_TICKS"
  fi
  log "=== view $view (viewer $VIEWER, $res) ==="
  CLIENT_STARTED=0
  setup_world
  build_arena
  if [ "$STACK_WAS_COLD" = 0 ] && [ "${PACK_PUSHED:-0}" = 1 ] && [ "$SERVER_RESTARTED" = 0 ]; then
    # parked clients re-download the pushed pack: wait for their resource reload before filming
    local n joined
    joined="$(rcon list 2>/dev/null)"
    for n in Watcher Caster Target; do
      load_client "$n" && grep -qw -- "$n" <<<"$joined" && wait_pack_applied "$n" "${PACK_RELOADS_BEFORE[$n]:-0}"
    done
  fi
  ensure_client "$VIEWER" "$W" "$H"
  # one at a time: Paper's connection throttle (4 s per address) drops a second simultaneous login
  if [ "$DUMMY_CASTER" = 1 ]; then ensure_client "Caster" 480 270; fi
  # the lobby plugin keeps relocating freshly joined players for a few seconds: let it finish, then
  # place the scene and re-place until everybody has stayed in the overworld
  local settle="$SETTLE"
  if [ "$CLIENT_STARTED" = 1 ]; then sleep "$JOIN_SETTLE"; else settle="$SETTLE_WARM"; fi
  reset_arena
  [ -n "$SKILL" ] && give_item Caster
  local tries ok who
  for tries in 1 2 3 4 5; do
    place_scene "$view" "$tz"
    sleep "$settle"
    ok=1
    for who in $(players_of "$view"); do in_arena "$who" || ok=0; done
    [ "$ok" = 1 ] && break
    log "players were moved out of the arena (attempt $tries), placing again"
  done
  [ "$ok" = 1 ] || die "could not keep the players in the overworld arena"
  rcon "kill @e[type=minecraft:item]" >/dev/null
  [ "$view" = compare ] || key "$VIEWER" 1
  sleep 1
}

# tick_gaps <dir>: the plan ticks (>= 0) of <dir>/framestamp.json that have no recorded frame of their own tick (comma list, empty = none)
tick_gaps() {
  jq -r '[(.map // [])[] | select(.exact == false and .tick >= 0) | .tick | floor] | unique | join(",")' "$1/framestamp.json" 2>/dev/null
}

run_view() { # view
  local view="$1" out="$OUT_ROOT/$SKILL/$1" attempt=0 gaps rate0="$SLOW_RATE"
  local max_attempts="${SHOT_TICK_ATTEMPTS:-4}"
  BEAT_ONLY=0
  while :; do
    attempt=$((attempt + 1))
    phase "preparing $view (attempt $attempt)"
    prepare_view "$view"
    # WARM_UP=1: cast the skill once at the normal tick rate first, so the software client has loaded every model / particle /
    # display it will see (a first-time asset stalls it for >1 s: that tick is then never drawn and a stepped capture is invalid)
    if [ "${WARM_UP:-1}" = 1 ] && [ "$STEPPED" = 1 ] && [ -n "$SKILL" ]; then
      local wsec; wsec="$(awk -v t="$TICKS" 'BEGIN{ s = t / 20 + 1.5; if (s > 9) s = 9; printf "%.1f", s }')"
      log "warm-up cast ($wsec s)"
      rcon "astera skill cast $SKILL Caster --force" >/dev/null 2>&1 || true
      sleep "$wsec"
      place_scene "$view" "$(target_z)"
      rcon "kill @e[type=minecraft:item]" >/dev/null
      sleep 2
    fi
    phase "filming $view (tick rate $SLOW_RATE)"
    film "$VIEWER" "$out"
    # Every plan tick must own a frame of THAT tick. A tick the client never drew (a render hitch longer than one slow tick, mostly
    # on heavy VFX) is refilmed at half the tick rate; a capture with a gap is never left behind as usable.
    gaps="$(tick_gaps "$out")"
    [ -z "$gaps" ] && break
    if [ "$STEPPED" != 1 ]; then   # explicit realtime (SHOT_REALTIME=1): capture_meta.py marks it captureQuality.invalid, publish-capture refuses it
      log "WARN: realtime capture: ticks [$gaps] have no frame of their own tick; the capture is marked invalid (not publishable)"
      break
    fi
    if [ "$attempt" -ge "$max_attempts" ]; then
      rcon "tick rate 20" >/dev/null 2>&1 || true
      rm -f "$out"/frame_*.png
      die "$view: ticks [$gaps] have no frame of their own tick after $attempt attempt(s) (last rate $SLOW_RATE tps). NOT usable, nothing published"
    fi
    SLOW_RATE="$(awk -v r="$SLOW_RATE" 'BEGIN{ r /= 2; if (r < 0.5) r = 0.5; print r }')"; export SLOW_RATE
    log "WARN: $view attempt $attempt missed ticks [$gaps]; refilming at $SLOW_RATE tps"
  done
  if [ "$FPS" = 20 ]; then make_outputs "$out" "$view"; else
    write_meta "$out" "$view" "$(ls "$out"/frame_*.png | wc -l)"
    log "FPS=$FPS: raw frames + meta.json (fps=$FPS, 3 frames per tick) in $out"
  fi
  rcon "kill @e[type=minecraft:husk]" >/dev/null
  SLOW_RATE="$rate0"; export SLOW_RATE   # only after the meta: capture_meta.py judges camera lateness on the rate the take ran at
  phase "view $view done (every tick has its own frame; $attempt attempt(s))"
}

# ---------------------------------------------------------------- status / up
# client_java <name>: pid of the Minecraft JVM under the recorded launcher process
client_java() { pgrep -P "${CPID[$1]}" java 2>/dev/null | head -n1; }

# proc_ticks <pid>: CPU clock ticks (user + system, all threads) used so far
proc_ticks() { awk '{print $14 + $15}' "/proc/$1/stat" 2>/dev/null; }

print_status() {
  local state have_sub=0 f n sig jp
  local -A cpu_before
  load_subnet && have_sub=1
  state="$(docker inspect -f '{{.State.Status}} since {{.State.StartedAt}}' "$SERVER_CT" 2>/dev/null || echo 'absent')"
  echo "stack      $PROJECT ($ROOT)"
  echo "server     $state${SUBNET:+, $SUBNET.0/24}"
  echo "memory     $(mem_available_gb) GB available (guard: SHOT_MIN_FREE_GB=${SHOT_MIN_FREE_GB:-8})"
  if [ "$have_sub" = 1 ] && [ -s "$STATE/deployed.json" ]; then
    read_inputs
    echo "content    deployed $(dget contentSourceHash) / working tree $IN_CONTENT  $([ "$(dget contentSourceHash)" = "$IN_CONTENT" ] && echo fresh || echo STALE)  (container hash $(dget deployedContentHash), verified $(dget contentVerified))"
    echo "pack       deployed $(dget packHash) / working tree $IN_PACK  $([ "$(dget packHash)" = "$IN_PACK" ] && echo fresh || echo STALE)"
    echo "plugins    deployed $(dget codeHash) / working tree $IN_CODE  $([ "$(dget codeHash)" = "$IN_CODE" ] && echo fresh || echo STALE)  (commit $(dget codeRevision | cut -c1-8), dirty $(dget codeDirty))"
  else
    echo "deployed   (no record: the next capture or vfx-shot-up creates the stack)"
  fi
  for f in "$STATE"/clients/*.env; do
    [ -f "$f" ] || continue
    n="$(basename "$f" .env)"
    if load_client "$n"; then
      jp="$(client_java "$n")"
      echo "client     $n alive (:${DISP[$n]}), $(sed -n 's/^SIG=//p' "$f")${jp:+, jvm $(awk '{print $3}' "/proc/$jp/stat" | sed 's/T/frozen between captures/;s/^[SRD]$/running/'), $(($(awk '/^VmRSS/{print $2}' "/proc/$jp/status") / 1024)) MB}"
      [ -n "${CPU:-}" ] && [ -n "$jp" ] && cpu_before[$n]="$(proc_ticks "$jp")"
    else
      echo "client     $n DEAD ($(sed -n 's/^SIG=//p' "$f"))"
    fi
  done
  if [ -n "${CPU:-}" ] && [ "${#cpu_before[@]}" -gt 0 ]; then
    # one full park cycle (2 s running + 8 s frozen) so the average means something
    sleep "${CPU_SECONDS:-10}"
    for n in "${!cpu_before[@]}"; do
      jp="$(client_java "$n")"
      echo "cpu        $n: $(( ($(proc_ticks "$jp") - cpu_before[$n]) / ${CPU_SECONDS:-10} ))% of one core over ${CPU_SECONDS:-10} s"
    done
  fi
  if [ -e "$STATE/stack.lock" ] && ! flock -n "$STATE/stack.lock" true 2>/dev/null; then echo "capture    RUNNING (stack lock held)"; else echo "capture    idle"; fi
  local lock="${SHOT_LOCK:-/tmp/claude-1000/astera-vfx-shot.lock}" i sf
  echo -n "slots      "
  for i in $(seq 1 "${SHOT_SLOTS:-1}"); do
    sf="$lock"; [ "$i" = 1 ] || sf="$lock.$i"
    if [ ! -e "$sf" ] || flock -n "$sf" true 2>/dev/null; then echo -n 'free '; else echo -n 'busy '; fi
  done
  echo "($lock)"
  if [ -s "$STATE/keep-until" ]; then echo "kept       until $(date -d "@$(cat "$STATE/keep-until")" '+%F %T') (SHOT_KEEP=1)"; fi
}

# ---------------------------------------------------------------- main
# `VFX_SHOT_SOURCED=1 . vfx-shot.sh` loads the functions above only (anim-shot.sh builds on them).
[ "$SOURCED" = 1 ] && return 0
command -v ffmpeg >/dev/null && command -v xdotool >/dev/null && command -v Xvfb >/dev/null \
  && command -v montage >/dev/null && command -v jq >/dev/null || die "need ffmpeg, xdotool, Xvfb, jq and ImageMagick (montage)"
if [ "$CMD" = status ]; then print_status; exit 0; fi
run_begin
thaw_clients
ensure_client_install
declare -A PACK_RELOADS_BEFORE
for n in Watcher Caster Target; do PACK_RELOADS_BEFORE[$n]="$(pack_reloads "$n")"; done
boot_server
if [ "$CMD" = up ]; then
  prepare_view "${VIEW%%,*}"
  phase "stack up: clients parked ($VIEWER${DUMMY_CASTER:+ + Caster})"
  print_status
  exit 0
fi
skill_list="$(rcon "astera skill list")"
if ! grep -qw -- "$SKILL" <<<"$skill_list"; then
  head -c 300 <<<"$skill_list" >&2
  die "skill '$SKILL' is not registered on the server (see: astera skill list)"
fi

if [ "$VIEW" = all ]; then views="caster opponent side"; else views="${VIEW//,/ }"; fi
for v in $views; do run_view "$v"; done
phase "done: $OUT_ROOT/$SKILL/"
cp "$PHASE_LOG" "$OUT_ROOT/$SKILL/phases.log" 2>/dev/null || true
