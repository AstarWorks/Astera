#!/usr/bin/env bash
# vfx-shot: cast one skill on an Astera server and film it with a REAL Minecraft client (headless: Xvfb + llvmpipe).
# Output: build/vfx-shot/<skill>/<view>/{frame_NNN.png,sheet.png,<skill>-<view>.gif,.mp4,meta.json}
#
#   make vfx-shot SKILL=couture-rapier-s2 [VIEW=caster|opponent|side|front|back|right|high|near|compare|all] [TICKS=60]
#   orbit views (spectator Watcher): side = 6 blocks off the caster's left, right = the opposite side, front = ahead beside
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

compose() {
  SHOT_SUBNET_PREFIX="$SUBNET" ASTERA_E2E_ID="$SHOT_ID" RCON_PASSWORD="$RCON_PASS" \
    docker compose -p "$PROJECT" -f "$ROOT/docker/docker-compose.e2e.yml" \
    -f "$HERE/compose.override.yml" --profile e2e "$@"
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
  rm -f "$STATE/run.done" "$STATE/abort"
  # this stack's own leftovers: reuse them only when a previous SHOT_KEEP=1 run kept them and they are within the TTL
  local ku
  ku="$(cat "$STATE/keep-until" 2>/dev/null || true)"
  if [ -n "$ku" ] && [ "$(date +%s)" -lt "${ku%.*}" ]; then
    log "reusing the kept stack $PROJECT (kept for another $(( ${ku%.*} - $(date +%s) ))s)"
  else
    reap_py --only-state "$STATE" --force --lock-held {STACK_LOCK_FD}>&- {SLOT_FD}>&- || true
  fi
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
FPS="${FPS:-20}"                     # recording rate; FPS=60 keeps 3 frames per tick (frame_NNN = NNN/3 ticks), no sheet/gif
RES="${RES:-}"                       # viewer resolution; default 1280x720 for compare, 640x360 for every other view
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
DISTANCE="${DISTANCE:-4}"            # caster -> target, blocks
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

CAMERA="${CAMERA:-case}"             # VIEW=compare: case = intent.json camera keys, side = fixed 6 blocks to the side, <name> = intent camera_presets.<name>
PRE_TICKS=0                          # frames filmed before the cast (compare only; cast_tick_ms = PRE_TICKS * 50)
ARENA_R=15                           # floor half-size around the arena centre
ARENA_H=12                           # air cleared above the floor
CMP_FOV_OPT=""
CMP_FOV_DEG=""
CAM0_CMD=""
ENEMY_X=4.2
case "$VIEW" in caster|opponent|side|front|back|right|high|near|compare|all) ;; *) die "VIEW must be caster|opponent|side|front|back|right|high|near|compare|all";; esac
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
[ -n "$ITEM" ] || ITEM="$(printf '%s' "$SKILL" | sed -E 's/-(s[0-9]+|def)$//')"

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
  if [ ! -x "$VENV/bin/portablemc" ]; then
    log "installing portablemc $PMC_VERSION into $VENV"
    mkdir -p "$CACHE"
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
# non-spectator player). HP 200, fresh per run (the husk is killed at the start and end of every view).
dummy_nbt() { # yaw
  printf '{Silent:1b,PersistenceRequired:1b,Rotation:[%sf,0f],attributes:[{id:"minecraft:max_health",base:200},{id:"minecraft:movement_speed",base:0},{id:"minecraft:follow_range",base:0},{id:"minecraft:attack_damage",base:0}],Health:200f}' "$1"
}

summon_target() {
  local tz
  tz="$(awk -v z="$CZ" -v d="$DISTANCE" 'BEGIN{printf "%.1f", z+d}')"
  rcon "execute in minecraft:overworld run summon minecraft:husk $CX $Y $tz $(dummy_nbt 180)" >/dev/null
}

target_z() { awk -v z="$CZ" -v d="$DISTANCE" 'BEGIN{printf "%.1f", z+d}'; }

give_item() { # player
  rcon "clear $1" >/dev/null
  grep -qi "gave" <<<"$(rcon "astera items give $1 $ITEM")" || log "WARN: could not give item '$ITEM' (set ITEM=<item-id>)"
}

# ---------------------------------------------------------------- capture
# film <viewer> <dir>: records the viewer's X display, then framestamp.py reads the tick stamp the client rendered into every
# frame and writes frame_NNN.png (stamp rectangle masked) + <dir>/framestamp.json (frame <-> tick map).
film() { # viewer view_dir
  local viewer="$1" dir="$2" rec="$OUT_ROOT/.rec-$SHOT_ID.mkv" d="${DISP[$1]}"
  local cast_stamp clog="$CLIENTS/$1/client.log" log_off pos_start pos_end fpid i end_wait survival=0
  log_off="$(stat -c %s "$clog" 2>/dev/null || echo 0)"
  rm -f "$rec"
  DISPLAY=":$d" xdotool mousemove 320 180 2>/dev/null || true
  # showinfo (before the rebase) logs each frame's wall-clock pts -> epoch time of the first frame
  ffmpeg -nostdin -loglevel info -y -copyts -use_wallclock_as_timestamps 1 -f x11grab -framerate "$FPS" \
    -video_size "$RES_FILM" -i ":$d" -vf "showinfo,setpts=PTS-STARTPTS" \
    -c:v libx264 -preset ultrafast -crf 10 -pix_fmt yuv444p -f matroska "$rec" 2>"$rec.log" &
  fpid=$!
  FILM_PID=$fpid
  for i in $(seq 1 100); do grep -q ' pts:' "$rec.log" 2>/dev/null && break; sleep 0.1; done
  sleep "$PRE_ROLL"
  pos_start="$(rcon "data get entity $viewer Pos" | sed 's/.*data: //') in $(rcon "data get entity $viewer Dimension" | sed 's/.*data: //')"
  log "caster before cast: $(rcon "data get entity Caster Pos" | sed 's/.*data: //') in $(rcon "data get entity Caster Dimension" | sed 's/.*data: //')"
  [ -s "$STATE/camera-plan.tsv" ] && [ "$VIEW_NOW" = compare ] || : > "$STATE/camera-plan.tsv"
  # one RCON connection: game time, cast, game time, then (compare) the camera cuts of the plan on the wall clock
  DIRECTOR_MARK_CMD="time query gametime" python3 -I "$HERE/director.py" "$SERVER_IP" "$RCON_PORT" "$RCON_PASS" "$STATE/camera-plan.tsv" "astera skill cast $SKILL Caster --force" \
    >"$STATE/director.stdout" 2>"$STATE/director.stderr" || { kill "$fpid" 2>/dev/null; cat "$STATE/director.stderr" >&2; die "director failed"; }
  cast_stamp="$(awk '/^STAMP/{print $2}' "$STATE/director.stderr")"
  [ -n "$cast_stamp" ] || { kill "$fpid" 2>/dev/null; die "cast command failed"; }
  # film until the motion (plus the pre-ticks of the window) is over
  end_wait="$(awk -v c="$cast_stamp" -v t="$TICKS" -v n="$(date +%s.%N)" 'BEGIN{w = c + t/20 + 0.8 - n; printf "%.2f", (w > 0 ? w : 0)}')"
  sleep "$end_wait"
  kill -TERM "$fpid" 2>/dev/null
  wait "$fpid" 2>/dev/null
  FILM_PID=""
  pos_end="$(rcon "data get entity $viewer Pos" | sed 's/.*data: //') in $(rcon "data get entity $viewer Dimension" | sed 's/.*data: //')"
  [ -s "$rec" ] || die "recording failed"
  rm -rf "$dir"; mkdir -p "$dir"
  cp "$STATE/director.stderr" "$dir/director-timing.log"
  # viewer session evidence for capture_meta.py: client log written during the recording + position before/after
  tail -c +"$((log_off + 1))" "$clog" > "$dir/viewer-client.log" 2>/dev/null || : > "$dir/viewer-client.log"
  printf '%s\n%s\n' "$pos_start" "$pos_end" > "$dir/viewer-pos.txt"
  local all="$dir"
  if [ -n "$BEAT_TICKS" ]; then all="$dir/.all"; mkdir -p "$all"; fi
  case "$VIEW_NOW" in caster|opponent) survival=1 ;; esac    # survival viewers have a hotbar (its items cannot be blanked by the pack)
  if ! python3 -I "$HERE/framestamp.py" "$rec" "$rec.log" "$STATE/director.stderr" "$cast_stamp" "$PRE_TICKS" "$TICKS" "$FPS" "$GUI_SCALE" "$survival" \
      "$all" "$dir/framestamp.json" > "$STATE/framestamp.out" 2>"$STATE/framestamp.err"; then
    if [ "${SHOT_KEEP_REC:-0}" = 1 ]; then mv "$rec" "$dir/recording.mkv"; mv "$rec.log" "$dir/recording.showinfo.log"; fi
    die "tick stamp could not be read ($(tail -1 "$STATE/framestamp.err")); SHOT_KEEP_REC=1 keeps the recording"
  fi
  log "tick stamp: $(cat "$STATE/framestamp.out")"
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
  VFX_SHOT_STATE="$STATE" VFX_SHOT_STACK_COLD="${STACK_WAS_COLD:-0}" \
    VFX_SHOT_BEAT_ONLY="${BEAT_ONLY:-0}" python3 -I "$@"
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
    meta_py "$HERE/capture_meta.py" "$ROOT/tools/skill-compare/data/$CASE/case.json" "$dir" "$INTENT"
  else
    printf '{"view":"%s","skill":"%s","fps":%d,"cast_tick_ms":0,"frames":%d,"fov":%s,"fovMode":"%s","resolution":"%s"}\n' \
      "$view" "$SKILL" "$FPS" "$n" "${FOV:-70}" "$fov_mode" "$RES_FILM" > "$dir/meta.json"
    meta_py "$HERE/capture_meta.py" - "$dir"
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
  local args=() i
  for ((i = 0; i < n; i += SHEET_STEP)); do
    args+=(-label "t+$((i - PRE_TICKS))" "$dir/$(printf 'frame_%03d.png' "$i")")
  done
  montage "${args[@]}" -tile "${SHEET_COLS}x" -geometry 320x180+3+3 -background '#151515' -fill white \
    -pointsize 16 "$dir/sheet.png" 2>/dev/null || log "WARN: montage failed"
  ffmpeg -nostdin -loglevel error -y -framerate 20 -i "$dir/frame_%03d.png" \
    -vf "scale=640:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=200[p];[b][p]paletteuse" \
    "$dir/$SKILL-$view.gif" || log "WARN: gif failed"
  ffmpeg -nostdin -loglevel error -y -framerate 20 -i "$dir/frame_%03d.png" -c:v libx264 -crf 16 \
    -pix_fmt yuv420p "$dir/$SKILL-$view.mp4" || log "WARN: mp4 failed"
  ffmpeg -nostdin -loglevel error -y -i "$dir/frame_%03d.png" -c:v libwebp -quality 85 \
    -compression_level 4 -threads 2 -start_number 0 "$dir/frame_%03d.webp" || log "WARN: web preview failed; serving PNG"
  write_meta "$dir" "$view" "$n"
  log "$view: $n frames -> $dir (sheet.png, $SKILL-$view.gif, $SKILL-$view.mp4)"
}

# ---------------------------------------------------------------- views
players_of() { case "$1" in caster) echo Caster ;; opponent) echo "Caster Target" ;; side|front|back|right|high|near|compare) echo "Caster Watcher" ;; esac; }

# orbit_cam <view>: "x y z yaw pitch" of the spectator camera for the orbit views, relative to the caster (CX, Y, CZ) facing
# +z with the target DISTANCE ahead. Each camera looks at the caster's chest / swing so the whole blade is in frame; front sits
# 2 blocks beside the target line so the target does not hide the caster; near = a bystander at eye height ~2.4 blocks away.
orbit_cam() {
  python3 -I -c '
import math, sys
v, cx, y, cz, d = sys.argv[1], *map(float, sys.argv[2:])
cams = {  # (camera offset, look-at offset) from the caster feet; x = +east = the caster left, z = forward
    "side": ((6.0, 1.0, d / 2), None),
    "right": ((-6.0, 1.0, d / 2), None),
    "front": ((2.0, 1.7, d + 3.0), (0.0, 1.2, 0.6)),
    "back": ((-1.0, 2.0, -5.0), (0.0, 1.2, 0.8)),
    "high": ((4.0, 5.8, -1.5), (0.0, 0.9, 1.0)),
    "near": ((2.2, 1.62, 1.2), (0.0, 1.2, 0.3)),
}
c, look = cams[v]
if look is None:
    yaw, pitch = (90.0 if c[0] > 0 else -90.0), 8.0
else:
    dx, dy, dz = (look[i] - c[i] for i in range(3))
    yaw = math.degrees(math.atan2(-dx, dz))
    pitch = -math.degrees(math.atan2(dy, math.hypot(dx, dz)))
print("%.2f %.2f %.2f %.1f %.1f" % (cx + c[0], y + c[1], cz + c[2], yaw, pitch))
' "$1" "$CX" "$Y" "$CZ" "$DISTANCE"
}


in_arena() { # player -> 0 when in the overworld, x/z in the 300s
  local dim pos
  dim="$(rcon "data get entity $1 Dimension")"
  pos="$(rcon "data get entity $1 Pos")"
  # the arena is centred on CX/CZ (300.5): x/z anywhere in 250..350 counts (a camera behind the caster is at x ~ 296)
  [[ "$dim" == *overworld* ]] && [[ "$pos" =~ \[(-?[0-9]+)\.[0-9]*d,\ [^,]*,\ (-?[0-9]+)\. ]] \
    && (( BASH_REMATCH[1] >= 250 && BASH_REMATCH[1] <= 350 && BASH_REMATCH[2] >= 250 && BASH_REMATCH[2] <= 350 ))
}

place_scene() { # view target_z
  local view="$1" tz="$2"
  rcon "kill @e[type=minecraft:husk]" >/dev/null
  rcon "gamemode survival Caster" >/dev/null
  [ "$view" = compare ] || tp Caster "$CX" "$Y" "$CZ" 0 0
  case "$view" in
    caster)
      summon_target ;;
    opponent)
      rcon "gamemode survival Target" >/dev/null
      # the target is a real survival player: without this a multi-hit skill kills it mid-capture (lobby return)
      rcon "attribute Target minecraft:max_health base set 200" >/dev/null
      rcon "effect give Target minecraft:instant_health 1 30 true" >/dev/null
      tp Target "$CX" "$Y" "$tz" 180 0 ;;
    compare)
      # caster faces +x (yaw -90); enemy ENEMY_X ahead looking back; spectator camera from the plan
      rcon "team add cmp" >/dev/null 2>&1
      rcon "team modify cmp nametagVisibility never" >/dev/null
      rcon "team join cmp Caster" >/dev/null
      tp Caster "$CX" "$Y" "$CZ" -90 0
      rcon "execute in minecraft:overworld run summon minecraft:husk $(awk -v x="$CX" -v d="$ENEMY_X" 'BEGIN{printf "%.2f", x+d}') $Y $CZ $(dummy_nbt 90)" >/dev/null
      rcon "gamemode spectator Watcher" >/dev/null
      rcon "$CAM0_CMD" >/dev/null ;;
    side|front|back|right|high|near)
      summon_target
      rcon "gamemode spectator Watcher" >/dev/null
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
    side|front|back|right|high|near) VIEWER="Watcher"; DUMMY_CASTER=1 ;;
    compare)  VIEWER="Watcher"; DUMMY_CASTER=1 ;;
  esac
  res="${RES:-}"
  if [ -z "$res" ]; then if [ "$view" = compare ]; then res=1280x720; else res=640x360; fi; fi
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

run_view() { # view
  local view="$1" out
  BEAT_ONLY=0
  prepare_view "$view"
  phase "filming $view"
  film "$VIEWER" "$OUT_ROOT/$SKILL/$view"
  out="$OUT_ROOT/$SKILL/$view"
  if [ "$FPS" = 20 ]; then make_outputs "$out" "$view"; else
    write_meta "$out" "$view" "$(ls "$out"/frame_*.png | wc -l)"
    log "FPS=$FPS: raw frames + meta.json (fps=$FPS, 3 frames per tick) in $out"
  fi
  rcon "kill @e[type=minecraft:husk]" >/dev/null
  phase "view $view done"
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
  prepare_view "$VIEW"
  phase "stack up: clients parked ($VIEWER${DUMMY_CASTER:+ + Caster})"
  print_status
  exit 0
fi
skill_list="$(rcon "astera skill list")"
if ! grep -qw -- "$SKILL" <<<"$skill_list"; then
  head -c 300 <<<"$skill_list" >&2
  die "skill '$SKILL' is not registered on the server (see: astera skill list)"
fi

if [ "$VIEW" = all ]; then views="caster opponent side"; else views="$VIEW"; fi
for v in $views; do run_view "$v"; done
phase "done: $OUT_ROOT/$SKILL/"
