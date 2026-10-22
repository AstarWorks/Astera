#!/usr/bin/env bash
# vfx-shot: cast one skill on a throwaway Astera server and film it with a REAL Minecraft client
# (headless: Xvfb + llvmpipe). Output: build/vfx-shot/<skill>/<view>/{frame_NNN.png,sheet.png,<skill>-<view>.gif,.mp4}
#
#   make vfx-shot SKILL=couture-rapier-s2 [VIEW=caster|opponent|side|all] [TICKS=60]
#   make vfx-shot SKILL=bastion-greatsword-s3 VIEW=compare CASE=zwei-s3     # camera from the skill-compare intent
#
# See tools/vfx-shot/README.md for the knobs and troubleshooting.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd -P)"
ROOT="$(cd "$HERE/../.." && pwd -P)"
# shellcheck source=lib.sh
. "$HERE/lib.sh"

SKILL="${SKILL:-}"
CASE="${CASE:-}"                     # VIEW=compare: skill-compare case id (tools/skill-compare/data/<case>/intent.json)
OUT_ROOT="${OUT_ROOT:-$ROOT/build/vfx-shot}"
# Run identity: unique per worktree path + case/skill, so parallel runs never share a compose project, image tag,
# container name, docker network or subnet. SHOT_PROJECT overrides the project name (SHOT_ID the image-tag id).
SHOT_ID="${SHOT_ID:-$(printf '%s|%s' "$ROOT" "${CASE:-$SKILL}" | sha1sum | cut -c1-8)}"
PROJECT="${SHOT_PROJECT:-astera-shot-$SHOT_ID}"
if [ "${1:-}" = down ]; then
  SUBNET="$(cat "$OUT_ROOT/.state/subnet" 2>/dev/null || echo 10.211.77)"
  docker network disconnect "${PROJECT}_default" "$(hostname)" >/dev/null 2>&1 || true
  SHOT_SUBNET_PREFIX="$SUBNET" ASTERA_E2E_ID="$SHOT_ID" RCON_PASSWORD=astera-shot docker compose -p "$PROJECT" \
    -f "$ROOT/docker/docker-compose.e2e.yml" -f "$HERE/compose.override.yml" --profile e2e \
    down -v --remove-orphans --rmi local
  exit 0
fi
[ -n "$SKILL" ] || die "SKILL=<skill-id> is required (e.g. SKILL=couture-rapier-s2)"
VIEW="${VIEW:-all}"
TICKS_SET="${TICKS:-}"
TICKS="${TICKS:-60}"                 # ticks filmed after the cast (compare: the case duration_ticks)
RES="${RES:-1280x720}"               # viewer resolution
HIDE_HUD="${HIDE_HUD:-1}"            # 1 = F1 (hand + world only), 0 = keep the HUD
GUI_SCALE="${GUI_SCALE:-2}"
FOV_OPT="${FOV_OPT:-0.0}"            # options.txt fov is normalised: 0.0 = 70, +-1 = 110 / 30
RENDER_DISTANCE="${RENDER_DISTANCE:-6}"
TIME_OF_DAY="${TIME_OF_DAY:-6000}"   # `time set`; 6000 = noon (target + floor readable), 13000 = dusk (glow pops, husk goes dark)
DISTANCE="${DISTANCE:-4}"            # caster -> target, blocks
JOIN_SETTLE="${JOIN_SETTLE:-8}"      # seconds after joining before the scene is placed (lobby relocations)
SETTLE="${SETTLE:-5}"                # seconds after the teleport before filming (chunks, join titles)
PRE_ROLL="${PRE_ROLL:-1.0}"          # seconds filmed before the cast (not in the frames)
SHEET_STEP="${SHEET_STEP:-2}"        # contact sheet shows every Nth tick
SHEET_COLS="${SHEET_COLS:-6}"
ITEM="${ITEM:-}"                     # item to hold; default: skill id minus -sN / -def
CAST_LAG_MS="${CAST_LAG_MS:-60}"     # client render + network lag between server cast and the frame
KEEP="${SHOT_KEEP:-0}"               # 1 = leave the server running (next run reuses it)

CAMERA="${CAMERA:-case}"             # VIEW=compare: case = intent.json camera keys, side = fixed 6 blocks to the side
PRE_TICKS=0                          # frames filmed before the cast (compare only; cast_tick_ms = PRE_TICKS * 50)
ARENA_R=15                           # floor half-size around the arena centre
ARENA_H=12                           # air cleared above the floor
CMP_FOV_OPT=""
CAM0_CMD=""
ENEMY_X=4.2
case "$VIEW" in caster|opponent|side|compare|all) ;; *) die "VIEW must be caster|opponent|side|compare|all";; esac
case "$CAMERA" in case|side) ;; *) die "CAMERA must be case|side";; esac
if [ "$VIEW" = compare ]; then
  [ -n "$CASE" ] || die "VIEW=compare needs CASE=<case-id> (e.g. CASE=zwei-s3)"
  INTENT="$(python3 -c 'import json,pathlib,sys; d=pathlib.Path(sys.argv[1]); c=json.loads((d/"case.json").read_text()); p=(d/c.get("intent",{}).get("file","intent.json")).resolve(); p.relative_to(d.resolve()); print(p)' "$ROOT/tools/skill-compare/data/$CASE")"
  [ -f "$INTENT" ] || { log "WARN: $INTENT missing, using defaults"; INTENT=-; }
  PRE_TICKS="${PRE_TICKS_CMP:-6}"
  ARENA_R=30
  ARENA_H=7
  FLOOR_BLOCK="${FLOOR_BLOCK:-minecraft:light_gray_concrete}"
fi
[ -n "$ITEM" ] || ITEM="$(printf '%s' "$SKILL" | sed -E 's/-(s[0-9]+|def)$//')"

SERVER_CT="$PROJECT-e2e-1"
NET="${PROJECT}_default"
RCON_PORT=25575
RCON_PASS="astera-shot"
STATE="$OUT_ROOT/.state"
CLIENTS="$OUT_ROOT/.clients"
CACHE="${SHOT_CACHE:-$HOME/.cache/astera-vfx-shot}"
VENV="$CACHE/venv"
MCDIR="$CACHE/mcdir"
MC_VERSION="${MC_VERSION:-26.3}"
PMC_VERSION="${PMC_VERSION:-5.0.6}"
mkdir -p "$STATE" "$CLIENTS"

declare -A DISP XPID CPID
SELF_CT="$(hostname)"
SERVER_IP=""
SUBNET=""

# ---------------------------------------------------------------- cleanup
cleanup() {
  local n
  for n in "${!CPID[@]}"; do stop_client "$n"; done
  if [ "$KEEP" != "1" ]; then
    docker network disconnect "$NET" "$SELF_CT" >/dev/null 2>&1 || true
    compose down -v --remove-orphans >/dev/null 2>&1 || true
    log "server project $PROJECT removed"
  else
    log "SHOT_KEEP=1: server left running (stop with: make vfx-shot-down)"
  fi
}

compose() {
  SHOT_SUBNET_PREFIX="$SUBNET" ASTERA_E2E_ID="$SHOT_ID" RCON_PASSWORD="$RCON_PASS" \
    docker compose -p "$PROJECT" -f "$ROOT/docker/docker-compose.e2e.yml" \
    -f "$HERE/compose.override.yml" --profile e2e "$@"
}

rcon() { python3 -I "$HERE/rcon.py" "$SERVER_IP" "$RCON_PORT" "$RCON_PASS" "$@"; }

# ---------------------------------------------------------------- server
create_network() {
  SUBNET="$(pick_subnet_prefix)" || { log "no free docker subnet 10.N.77.0/24 (N=211..250)"; return 1; }
  echo "$SUBNET" > "$STATE/subnet"
  compose up --no-build --no-start e2e e2e-resource-pack > "$STATE/compose-create.log" 2>&1 \
    || { tail -30 "$STATE/compose-create.log" >&2; return 1; }
}

boot_server() {
  if docker inspect "$SERVER_CT" >/dev/null 2>&1 && [ -s "$STATE/subnet" ]; then
    SUBNET="$(cat "$STATE/subnet")"
    log "reusing running server (subnet $SUBNET)"
  else
    log "building server image (project $PROJECT); first build takes minutes"
    SUBNET="10.211.77"   # placeholder for the compose interpolation of `build` (not used there)
    compose build e2e e2e-resource-pack > "$STATE/compose-build.log" 2>&1 \
      || { tail -30 "$STATE/compose-build.log" >&2; die "server image build failed"; }
    # subnet pick + network/container creation is one critical section across all parallel runs
    # (otherwise two runs probe the same free 10.N.77.0/24 and the second `up` fails with "Pool overlaps")
    with_global_lock subnet create_network || die "could not create the compose network"
    log "starting server (project $PROJECT, subnet $SUBNET.0/24)"
    compose up -d --no-build --wait e2e e2e-resource-pack > "$STATE/compose-up.log" 2>&1 \
      || { tail -30 "$STATE/compose-up.log" >&2; die "server failed to start"; }
  fi
  SERVER_IP="$SUBNET.2"
  docker network connect "$NET" "$SELF_CT" >/dev/null 2>&1 || true
  log "waiting for RCON at $SERVER_IP:$RCON_PORT"
  local i
  for i in $(seq 1 60); do
    rcon list >/dev/null 2>&1 && return 0
    sleep 2
  done
  die "RCON never came up"
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
fov:$FOV_OPT
bobView:false
renderDistance:$RENDER_DISTANCE
simulationDistance:6
particles:0
guiScale:$GUI_SCALE
pauseOnLostFocus:false
maxFps:60
enableVsync:false
soundCategory_master:0.0
tutorialStep:none
onboardAccessibility:false
skipMultiplayerWarning:true
joinedFirstServer:true
renderClouds:"false"
resourcePacks:["vanilla","file/astera-shot-fix.zip"]
EOF
  # pre-accept the server resource pack (Quick Play looks the address up in servers.dat)
  python3 -I "$HERE/servers_dat.py" "$wd/servers.dat" "$SERVER_IP:25565"
  # Astera's pack stores item textures under textures/generated/items + textures/items, which no vanilla
  # atlas source covers -> every Astera item renders magenta. This overlay adds the missing atlas sources.
  python3 -I "$HERE/mkzip.py" "$HERE/overlay-pack" "$wd/resourcepacks/astera-shot-fix.zip"
}

start_client() { # name width height
  local name="$1" w="$2" h="$3" d wd="$CLIENTS/$1" i
  write_client_files "$name" "$w" "$h"
  # -displayfd: the X server picks a free display number itself and writes it to stdout (no race between runs)
  : > "$wd/display"
  Xvfb -displayfd 1 -screen 0 "${w}x${h}x24" -nolisten tcp >"$wd/display" 2>"$wd/xvfb.log" &
  XPID[$name]=$!
  d=""
  for i in $(seq 1 100); do d="$(tr -dc '0-9' <"$wd/display")"; [ -n "$d" ] && break; sleep 0.1; done
  [ -n "$d" ] || die "Xvfb did not report a display (see $wd/xvfb.log)"
  DISP[$name]="$d"
  : > "$wd/client.log"
  DISPLAY=":$d" LIBGL_ALWAYS_SOFTWARE=1 LP_NUM_THREADS="${LP_NUM_THREADS:-4}" XDG_RUNTIME_DIR="$wd" \
    setsid "$VENV/bin/portablemc" --main-dir "$MCDIR" start "$MC_VERSION" --mc-dir "$wd" -u "$name" \
    --join-server "$SERVER_IP" --join-server-port 25565 --resolution "${w}x${h}" \
    >"$wd/client.log" 2>&1 &
  CPID[$name]=$!
  log "client $name on :$d ($w x $h)"
}

stop_client() {
  local n="$1"
  [ -n "${CPID[$n]:-}" ] && kill -TERM -- "-${CPID[$n]}" 2>/dev/null
  [ -n "${XPID[$n]:-}" ] && kill "${XPID[$n]}" 2>/dev/null
  sleep 0.5
  [ -n "${CPID[$n]:-}" ] && kill -KILL -- "-${CPID[$n]}" 2>/dev/null
  unset "CPID[$n]" "XPID[$n]" "DISP[$n]"
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
film() { # viewer view_dir
  local viewer="$1" dir="$2" rec="$OUT_ROOT/.rec-$$.mkv" d="${DISP[$1]}"
  local dur t_total cast_stamp first_pts offset
  dur="$(awk -v t="$TICKS" -v p="$PRE_ROLL" 'BEGIN{printf "%.2f", p + t/20 + 2.0}')"
  local cast_cmd="astera skill cast $SKILL Caster --force"
  t_total="$(awk -v d="$dur" 'BEGIN{printf "%.2f", d + 1.5}')"
  rm -f "$rec"
  # showinfo (before the rebase) logs each frame's wall-clock pts -> epoch time of the first frame
  ffmpeg -nostdin -loglevel info -y -copyts -use_wallclock_as_timestamps 1 -f x11grab -framerate 20 \
    -video_size "$RES_FILM" -i ":$d" -t "$t_total" -vf "showinfo,setpts=PTS-STARTPTS" \
    -c:v libx264 -preset ultrafast -crf 10 -pix_fmt yuv444p -f matroska "$rec" 2>"$rec.log" &
  local fpid=$!
  for i in $(seq 1 100); do grep -q ' pts:' "$rec.log" 2>/dev/null && break; sleep 0.1; done
  sleep "$PRE_ROLL"
  log "caster before cast: $(rcon "data get entity Caster Pos" | sed 's/.*data: //') in $(rcon "data get entity Caster Dimension" | sed 's/.*data: //')"
  if [ "$VIEW" = compare ]; then
    # one RCON connection: cast, then the camera cuts of the plan on the wall clock
    python3 -I "$HERE/director.py" "$SERVER_IP" "$RCON_PORT" "$RCON_PASS" "$STATE/camera-plan.tsv" "$cast_cmd" >"$STATE/director.stdout" 2>"$STATE/director.stderr" || { cat "$STATE/director.stderr" >&2; die "director failed"; }
    cast_stamp="$(awk '/^STAMP/{print $2}' "$STATE/director.stderr")"
  else
    cast_stamp="$(rcon --stamp "$cast_cmd" 2>&1 >/dev/null | awk '/^STAMP/{print $2}')"
  fi
  [ -n "$cast_stamp" ] || { kill "$fpid" 2>/dev/null; die "cast command failed"; }
  wait "$fpid"
  [ -s "$rec" ] || die "recording failed"
  # showinfo's `pts:` is in microseconds (wall-clock timebase 1/1e6)
  first_pts="$(grep -m1 -o ' pts:[0-9]*' "$rec.log" | tr -dc '0-9' | awk '{printf "%.6f", $1/1000000}')"
  [ -n "$first_pts" ] || die "could not read the first frame time from $rec.log"
  offset="$(awk -v c="$cast_stamp" -v f="$first_pts" -v l="$CAST_LAG_MS" 'BEGIN{printf "%.3f", c - f + l/1000.0 - '"$PRE_TICKS"'/20.0}')"
  last_pts="$(grep -o ' pts:[0-9]*' "$rec.log" | tail -1 | tr -dc '0-9' | awk '{printf "%.3f", $1/1000000}')"
  log "cast at +${offset}s into the recording (cast $cast_stamp, first frame $first_pts, last frame $last_pts)"
  rm -rf "$dir"; mkdir -p "$dir"
  if [ "$VIEW" = compare ]; then cp "$STATE/director.stderr" "$dir/director-timing.log"; fi
  ffmpeg -nostdin -loglevel error -y -i "$rec" \
    -vf "fps=20:start_time=$offset,setpts=N/20/TB" -frames:v "$((TICKS + PRE_TICKS + 1))" -start_number 0 "$dir/frame_%03d.png" \
    || die "frame extraction failed"
  rm -f "$rec" "$rec.log"
}

make_outputs() { # dir view
  local dir="$1" view="$2" n
  n="$(ls "$dir"/frame_*.png 2>/dev/null | wc -l)"
  [ "$n" -gt 0 ] || die "no frames extracted into $dir"
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
  if [ "$view" = compare ]; then
    printf '{"view":"compare","case":"%s","camera":"%s","skill":"%s","fps":20,"cast_tick_ms":%d,"frames":%d}\n' \
      "$CASE" "$CAMERA" "$SKILL" "$((PRE_TICKS * 50))" "$n" > "$dir/meta.json"
    python3 -I "$HERE/capture_meta.py" "$ROOT/tools/skill-compare/data/$CASE/case.json" "$dir" "$INTENT"
  fi
  log "$view: $n frames -> $dir (sheet.png, $SKILL-$view.gif, $SKILL-$view.mp4)"
}

# ---------------------------------------------------------------- views
players_of() { case "$1" in caster) echo Caster ;; opponent) echo "Caster Target" ;; side|compare) echo "Caster Watcher" ;; esac; }

in_arena() { # player -> 0 when in the overworld, x/z in the 300s
  local dim pos
  dim="$(rcon "data get entity $1 Dimension")"
  pos="$(rcon "data get entity $1 Pos")"
  [[ "$dim" == *overworld* ]] && [[ "$pos" == *"["[0-9]*3[0-9][0-9]* ]]
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
    side)
      summon_target
      rcon "gamemode spectator Watcher" >/dev/null
      tp Watcher "$(awk -v x="$CX" 'BEGIN{printf "%.1f", x+6}')" "$(awk -v y="$Y" 'BEGIN{printf "%.1f", y+1.0}')" \
        "$(awk -v z="$CZ" -v d="$DISTANCE" 'BEGIN{printf "%.1f", z+d/2}')" 90 8 ;;
  esac
}

run_view() { # view
  local view="$1" viewer dummy_caster=0 W H tz
  tz="$(target_z)"
  RES_FILM="$RES"
  W="${RES%x*}"; H="${RES#*x}"
  case "$view" in
    caster)   viewer="Caster" ;;
    opponent) viewer="Target"; dummy_caster=1 ;;
    side)     viewer="Watcher"; dummy_caster=1 ;;
    compare)  viewer="Watcher"; dummy_caster=1 ;;
  esac
  if [ "$view" = compare ]; then
    eval "$(python3 -I "$HERE/compare_plan.py" "$INTENT" "$CAMERA" "$CX" "$Y" "$CZ" "$STATE/camera-plan.tsv")"
    FOV_OPT="$CMP_FOV_OPT"
    [ -n "$TICKS_SET" ] || TICKS="$DURATION_TICKS"
  fi
  log "=== view $view (viewer $viewer) ==="
  setup_world
  build_arena
  start_client "$viewer" "$W" "$H"
  wait_client_ready "$viewer"
  # one at a time: Paper's connection throttle (4 s per address) drops a second simultaneous login
  if [ "$dummy_caster" = 1 ]; then
    start_client "Caster" 480 270
    wait_client_ready "Caster"
  fi

  # the lobby plugin keeps relocating freshly joined players for a few seconds: let it finish, then
  # place the scene and re-place until everybody has stayed in the overworld
  sleep "$JOIN_SETTLE"
  give_item Caster
  local tries ok who
  for tries in 1 2 3 4 5; do
    place_scene "$view" "$tz"
    sleep "$SETTLE"
    ok=1
    for who in $(players_of "$view"); do in_arena "$who" || ok=0; done
    [ "$ok" = 1 ] && break
    log "players were moved out of the arena (attempt $tries), placing again"
  done
  [ "$ok" = 1 ] || die "could not keep the players in the overworld arena"
  rcon "kill @e[type=minecraft:item]" >/dev/null
  [ "$view" = compare ] || key "$viewer" 1
  if [ "$HIDE_HUD" = 1 ]; then key "$viewer" F1; fi
  DISPLAY=":${DISP[$viewer]}" xdotool mousemove 640 360 2>/dev/null || true
  sleep 1
  film "$viewer" "$OUT_ROOT/$SKILL/$view"
  make_outputs "$OUT_ROOT/$SKILL/$view" "$view"
  stop_client "$viewer"
  [ "$dummy_caster" = 1 ] && stop_client "Caster"
  rcon "kill @e[type=minecraft:husk]" >/dev/null
}

# ---------------------------------------------------------------- main
trap cleanup EXIT
trap 'exit 130' INT TERM
acquire_slot   # global concurrency limit across worktrees; held (fd) until this process exits
command -v ffmpeg >/dev/null && command -v xdotool >/dev/null && command -v Xvfb >/dev/null \
  && command -v montage >/dev/null || die "need ffmpeg, xdotool, Xvfb and ImageMagick (montage)"
ensure_client_install
boot_server
skill_list="$(rcon "astera skill list")"
if ! grep -qw -- "$SKILL" <<<"$skill_list"; then
  head -c 300 <<<"$skill_list" >&2
  die "skill '$SKILL' is not registered on the server (see: astera skill list)"
fi

if [ "$VIEW" = all ]; then views="caster opponent side"; else views="$VIEW"; fi
for v in $views; do run_view "$v"; done
log "done: $OUT_ROOT/$SKILL/"
