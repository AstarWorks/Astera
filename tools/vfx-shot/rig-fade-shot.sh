#!/usr/bin/env bash
# rig-fade-shot: verify the skin-rig first-person camera-distance fade (ADR-0285) in a REAL client.
# Films the same animation from the CASTER's own client in first person and in F5 (third person, back), plus a bystander
# (Watcher) 2.4 blocks off the side (case "std"), and the cases where the fade must NOT trigger (ADR-0293): F5 with a wall
# directly behind, F5 under a low ceiling, F5 front view facing a wall, a bystander camera 0.45 / 0.6 / 1.0 blocks from the
# casting player, and the couture-rapier-s2 dash + ghost set (case "ghost").
# Output: build/vfx-shot/rig-fade[-TAG]/<case>-<view>/frame_NNN.png and sheet-<case>-<view>.png contact sheets.
#   make rig-fade-shot [ANIM=anim-demo-sword-swing] [CASES="std wall-back ceiling wall-front bystander ghost"] [OUT_TAG=before]
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd -P)"
ROOT="$(cd "$HERE/../.." && pwd -P)"
ARG="${1:-${ANIM:-anim-demo-sword-swing}}"
set --
ANIM_ARG="$ARG"
anim_length() { # skill id -> animation length in ticks (SHOT_LENGTH when the skill plays no animation)
  local a l
  a="$(sed -nE 's/^[[:space:]]*animation:[[:space:]]*"?([a-z0-9-]+)"?.*/\1/p' "$ROOT/content/skills/$1.yaml" | head -1)"
  l=""; [ -n "$a" ] && l="$(sed -nE 's/^length_ticks:[[:space:]]*([0-9]+).*/\1/p' "$ROOT/content/animations/$a.yaml" | head -1)"
  echo "${l:-${SHOT_LENGTH:-100}}"
}
LENGTH="$(anim_length "$ARG")"
SKILL="$ARG"; ITEM="${ITEM:-bastion-greatsword}"; VIEW=side; VFX_SHOT_SOURCED=1
# shellcheck source=vfx-shot.sh
. "$HERE/vfx-shot.sh"
OUT="$OUT_ROOT/rig-fade${OUT_TAG:+-$OUT_TAG}"
TICKS=$((LENGTH + 18))
export FOV_EFFECT_SCALE=0.0   # lock_movement slows the caster, which would narrow the FOV and zoom the capture

# rec <dir-prefix> <viewer>...: record every viewer's display at once, cast over RCON, extract 20 fps frames
# (frame_NNN = about tick NNN-2 after the cast) into <dir-prefix>-<viewer>.
rec() {
  local prefix="$1"; shift
  local v i stamp first off t_total
  local -A pid
  t_total="$(awk -v t=$TICKS 'BEGIN{printf "%.2f", 1.5 + t/20 + 2}')"
  for v in "$@"; do
    rm -f "$OUT/.rec-$v.mkv"
    ffmpeg -nostdin -loglevel info -y -copyts -use_wallclock_as_timestamps 1 -f x11grab -framerate 20 \
      -video_size "$RES" -i ":${DISP[$v]}" -t "$t_total" -vf "showinfo,setpts=PTS-STARTPTS" \
      -c:v libx264 -preset ultrafast -crf 10 -pix_fmt yuv444p -f matroska "$OUT/.rec-$v.mkv" 2>"$OUT/.rec-$v.log" &
    pid[$v]=$!
  done
  for v in "$@"; do
    for i in $(seq 1 100); do grep -q ' pts:' "$OUT/.rec-$v.log" 2>/dev/null && break; sleep 0.1; done
  done
  sleep 1.5
  stamp="$(rcon --stamp "astera skill cast $SKILL Caster --force" 2>&1 >/dev/null | awk '/^STAMP/{print $2}')"
  [ -n "$stamp" ] || { kill "${pid[@]}" 2>/dev/null; die "cast failed"; }
  for v in "$@"; do
    wait "${pid[$v]}"
    first="$(grep -m1 -o ' pts:[0-9]*' "$OUT/.rec-$v.log" | tr -dc '0-9' | awk '{printf "%.6f", $1/1000000}')"
    off="$(awk -v c="$stamp" -v f="$first" 'BEGIN{printf "%.3f", c - f + 0.06 - 0.10}')"
    rm -rf "$OUT/$prefix-$v"; mkdir -p "$OUT/$prefix-$v"
    ffmpeg -nostdin -loglevel error -y -i "$OUT/.rec-$v.mkv" -vf "fps=20:start_time=$off,setpts=N/20/TB" -frames:v "$((TICKS + 3))" \
      -start_number 0 "$OUT/$prefix-$v/frame_%03d.png" || die "frame extraction failed"
    rm -f "$OUT/.rec-$v.mkv" "$OUT/.rec-$v.log"
  done
  sleep "$(awk -v t=$LENGTH 'BEGIN{printf "%.1f", t/20 + 1.5}')"
  tp Caster $CASTER_POSE; sleep 1.5
}

prep_skill() { SKILL="$1"; LENGTH="$(anim_length "$1")"; TICKS=$((LENGTH + 18)); }

# perspective <0 first person | 1 F5 back | 2 F5 front>: cycle the caster's client to it (F5 cycles 0 -> 1 -> 2 -> 0).
PERSP=0
perspective() {
  local n=$(( ($1 - PERSP + 3) % 3 ))
  while [ "$n" -gt 0 ]; do key Caster F5; sleep 0.4; n=$((n - 1)); done
  PERSP=$1; sleep 1.5
}

# arena_clear: remove the blocks of the previous case around the caster (the floor is at Y-1 and stays).
arena_clear() { rcon "execute in minecraft:overworld run fill $((${CX%.*} - 8)) $Y $((${CZ%.*} - 8)) $((${CX%.*} + 8)) $((Y + 8)) $((${CZ%.*} + 8)) minecraft:air" >/dev/null; sleep 0.5; }
# arena_wall <dx0> <dz0> <dx1> <dz1> <dy0> <dy1>: stone box in block cells relative to the caster's cell.
arena_wall() { rcon "execute in minecraft:overworld run fill $((${CX%.*} + $1)) $((Y + $5)) $((${CZ%.*} + $2)) $((${CX%.*} + $3)) $((Y + $6)) $((${CZ%.*} + $4)) minecraft:stone" >/dev/null; sleep 1.0; }

# sheet <dir> <out.png>: contact sheet, one tile per 2 ticks, labelled with the frame number.
sheet() {
  local d="$1" out="$2" files=() t f
  for t in $(seq "${SHEET_FROM:-4}" "${SHEET_STEP:-2}" "${SHEET_TO:-28}"); do
    f="$d/$(printf 'frame_%03d.png' "$t")"; [ -f "$f" ] && files+=(-label "$t" "$f")
  done
  montage "${files[@]}" -tile 4x -geometry 640x360+4+4 "$out" 2>/dev/null || log "WARN: montage failed for $d"
}


run_begin
ensure_client_install
boot_server
mkdir -p "$OUT"
setup_world
build_arena
start_client Watcher "${RES%x*}" "${RES#*x}"; wait_client_ready Watcher
start_client Caster "${RES%x*}" "${RES#*x}"; wait_client_ready Caster
sleep "$JOIN_SETTLE"
give_item Caster
rcon "gamemode spectator Watcher" >/dev/null
rcon "gamemode survival Caster" >/dev/null
CASTER_POSE="$CX $Y $CZ 0 0"   # facing +z; "behind" is -z
tp Caster $CASTER_POSE; tp Watcher "$CX" "$Y" "$CZ" 0 0; sleep "$SETTLE"
rcon "kill @e[type=minecraft:item]" >/dev/null
key Watcher 1; key Caster 1
key Watcher F1; key Caster F1
DISPLAY=":${DISP[Caster]}" xdotool mousemove 100 100 2>/dev/null || true

# watcher_at <distance>: the Watcher (spectator camera at eye height) <distance> blocks west of the caster, looking at them.
watcher_at() { tp Watcher "$(awk -v cx=$CX -v d="$1" 'BEGIN{print cx-d}')" "$Y" "$CZ" -90 0; sleep 1.3; }
case_sheet() { # <case-view> <viewer>...
  local c="$1" v; shift
  for v in "$@"; do sheet "$OUT/$c-$v" "$OUT/sheet-$c-$v.png"; done
}

for c in ${CASES:-std wall-back ceiling wall-front bystander ghost}; do
  log "case $c"
  arena_clear; perspective 0
  CASTER_POSE="$CX $Y $CZ 0 0"
  case "$c" in
    std) # ADR-0285 set: first person + bystander 2.4 blocks away, then F5 back (vanilla distance 4 blocks)
      prep_skill "$ARG"; tp Caster $CASTER_POSE; watcher_at 2.4
      rec std-fp Caster Watcher
      perspective 1; rec std-f5 Caster
      case_sheet std-fp Caster Watcher; case_sheet std-f5 Caster ;;
    ghost) # couture-rapier-s2: a 9-block dash and ghost afterimages
      prep_skill couture-rapier-s2; tp Caster $CASTER_POSE; watcher_at 2.4
      rec ghost-fp Caster Watcher
      perspective 1; rec ghost-f5 Caster
      case_sheet ghost-fp Caster Watcher; case_sheet ghost-f5 Caster ;;
    wall-back) # F5 back with a stone wall one cell behind the caster: vanilla pulls the camera to ~0.3 blocks
      prep_skill "$ARG"; arena_wall -6 -1 6 -1 0 5; tp Caster $CASTER_POSE; watcher_at 2.4
      perspective 1; rec wall-back-f5 Caster; case_sheet wall-back-f5 Caster ;;
    ceiling) # F5 back, looking down 35 deg, under a 2-block ceiling: the camera is pushed down toward the head
      prep_skill "$ARG"; arena_wall -6 -6 6 6 2 2; CASTER_POSE="$CX $Y $CZ 0 35"; tp Caster $CASTER_POSE; watcher_at 2.4
      perspective 1; rec ceiling-f5 Caster; case_sheet ceiling-f5 Caster ;;
    wall-front) # F5 front view with a wall one cell ahead: the camera sits between the caster and the wall
      prep_skill "$ARG"; arena_wall -6 1 6 1 0 5; tp Caster $CASTER_POSE; watcher_at 2.4
      perspective 2; rec wall-front-f5 Caster; case_sheet wall-front-f5 Caster ;;
    bystander) # another player's camera next to the casting rig
      prep_skill "$ARG"; tp Caster $CASTER_POSE
      for d in ${BYSTANDER_DISTS:-0.45 0.6 1.0}; do
        watcher_at "$d"; rec "bystander-$d" Watcher; case_sheet "bystander-$d" Watcher
      done ;;
    idle) # NORMAL state, F5 back, no cast: is the player's own body drawn normally (not translucent)? before a cast, after one, and on a scoreboard team
      prep_skill "$ARG"; tp Caster $CASTER_POSE; watcher_at 2.4
      mkdir -p "$OUT/idle"
      snap() { DISPLAY=":${DISP[Caster]}" import -window root "$OUT/idle/$1.png" 2>/dev/null || log "WARN: snap $1 failed"; }
      perspective 1; sleep 1
      snap f5-1-before-cast
      rcon "astera skill cast $SKILL Caster --force" >/dev/null; sleep "$(awk -v t=$LENGTH 'BEGIN{printf "%.1f", t/20 + 4}')"; tp Caster $CASTER_POSE; sleep 1.5
      snap f5-2-after-cast
      rcon "team add rigtest" >/dev/null; rcon "team join rigtest Caster" >/dev/null; sleep 1.5
      snap f5-3-team-idle
      rcon "astera skill cast $SKILL Caster --force" >/dev/null; sleep 1.2
      snap f5-4-team-casting
      sleep "$(awk -v t=$LENGTH 'BEGIN{printf "%.1f", t/20 + 3}')"; tp Caster $CASTER_POSE; sleep 1.5
      snap f5-5-team-after-cast
      rcon "team leave Caster" >/dev/null; rcon "team remove rigtest" >/dev/null
      montage "$OUT"/idle/f5-*.png -tile 3x -geometry 800x450+4+4 "$OUT/sheet-idle.png" 2>/dev/null || log "WARN: montage idle" ;;
    lights) # natural light vs the rig marker (block 14): a player (Watcher), a zombie, a villager and the caster stand in cells lit by a lantern / torch / glowstone, outdoors (sky 15) and in a closed stone box (sky 0); NO cast, nothing here is a rig
      mkdir -p "$OUT/lights"
      bx=${CX%.*}; bz=${CZ%.*}
      lsnap() { DISPLAY=":${DISP[$1]}" import -window root "$OUT/lights/$2.png" 2>/dev/null || log "WARN: snap $2 failed"; }
      # station <name> <block> <cell|center> <r>: ring of 4 cells (caster S, zombie W, villager E, watcher N) at radius r around C=(bx,bz+4);
      # center = one source in C (entity cells are r away, light 15-r), cell = a source in every entity cell (the entity stands inside it)
      lstation() {
        local name="$1" blk="$2" mode="$3" r="$4" env="$5" cs ws es ns
        if [ -n "${LIGHT_ONLY:-}" ] && ! echo " $LIGHT_ONLY " | grep -q " $name "; then return; fi
        arena_clear; rcon "kill @e[type=minecraft:zombie]" >/dev/null; rcon "kill @e[type=minecraft:villager]" >/dev/null
        if [ "$env" = indoor ]; then
          rcon "execute in minecraft:overworld run fill $((bx - 6)) $((Y - 1)) $((bz - 5)) $((bx + 6)) $((Y + 4)) $((bz + 8)) minecraft:stone hollow" >/dev/null
        fi
        cs="$bx $((bz + 4 - r))"; ws="$((bx - r)) $((bz + 4))"; es="$((bx + r)) $((bz + 4))"; ns="$bx $((bz + 4 + r))"
        if [ "$mode" = none ]; then :
        elif [ "$mode" = center ]; then
          rcon "execute in minecraft:overworld run setblock $bx $((Y + 1)) $((bz + 4)) $blk" >/dev/null
        else
          for p in "$cs" "$ws" "$es" "$ns"; do set -- $p; rcon "execute in minecraft:overworld run setblock $1 $((Y + 1)) $2 $blk" >/dev/null; done
        fi
        set -- $ws; rcon "execute in minecraft:overworld run summon minecraft:zombie $1.5 $Y $2.5 {NoAI:1b,Silent:1b,PersistenceRequired:1b,LeftHanded:1b,Rotation:[180f,0f],equipment:{head:{id:\"minecraft:iron_helmet\",count:1},mainhand:{id:\"minecraft:iron_sword\",count:1}}}" >/dev/null
        set -- $es; rcon "execute in minecraft:overworld run summon minecraft:villager $1.5 $Y $2.5 {NoAI:1b,Silent:1b,PersistenceRequired:1b,Rotation:[180f,0f]}" >/dev/null
        set -- $ns; tp Watcher "$1.5" "$Y" "$2.5" 180 0
        set -- $cs; tp Caster "$1.5" "$Y" "$2.5" 0 0
        sleep 3
        perspective 0; sleep 0.5; lsnap Caster "$name-$env-1fp"
        lsnap Watcher "$name-$env-3watcher"
        perspective 1; sleep 0.5; lsnap Caster "$name-$env-2f5"
        perspective 0
        montage -label "$name $env: caster fp | caster F5 | watcher" "$OUT/lights/$name-$env-1fp.png" "$OUT/lights/$name-$env-2f5.png" "$OUT/lights/$name-$env-3watcher.png" -tile 3x -geometry 640x360+4+4 "$OUT/sheet-lights-$name-$env.png" 2>/dev/null || log "WARN: montage $name"
      }
      rcon "gamemode survival Watcher" >/dev/null
      for env in ${LIGHT_ENVS:-outdoor indoor}; do
        lstation control "minecraft:air" none 1 $env
        lstation lantern-l14 "minecraft:lantern" center 1 $env
        lstation lantern-l13 "minecraft:lantern" center 2 $env
        lstation torch-l14 "minecraft:torch" cell 2 $env
        lstation torch-l13 "minecraft:torch" center 1 $env
        lstation glowstone-l14 "minecraft:glowstone" center 1 $env
        lstation glowstone-l13 "minecraft:glowstone" center 2 $env
        lstation lightblock-l14 "minecraft:light[level=14]" cell 2 $env
        lstation lightblock-l15 "minecraft:light[level=15]" cell 2 $env
      done
      # the founder's case: walk up to a lantern with another player and a mob beside it (F5 back, still every second)
      [ -n "${LIGHT_ONLY:-}" ] && { rcon "gamemode spectator Watcher" >/dev/null; continue; }
      arena_clear; rcon "kill @e[type=minecraft:zombie]" >/dev/null; rcon "kill @e[type=minecraft:villager]" >/dev/null
      rcon "execute in minecraft:overworld run setblock $bx $((Y + 1)) $((bz + 4)) minecraft:lantern" >/dev/null
      rcon "execute in minecraft:overworld run summon minecraft:zombie $((bx - 1)).5 $Y $((bz + 4)).5 {NoAI:1b,Silent:1b,PersistenceRequired:1b,LeftHanded:1b,Rotation:[180f,0f],equipment:{head:{id:\"minecraft:iron_helmet\",count:1},mainhand:{id:\"minecraft:iron_sword\",count:1}}}" >/dev/null
      rcon "execute in minecraft:overworld run summon minecraft:villager $((bx + 1)).5 $Y $((bz + 4)).5 {NoAI:1b,Silent:1b,PersistenceRequired:1b,Rotation:[180f,0f]}" >/dev/null
      tp Watcher "$bx.5" "$Y" "$((bz + 5)).5" 180 0
      tp Caster "$bx.5" "$Y" "$((bz - 3)).5" 0 0; sleep 3
      perspective 1; sleep 0.5
      ( sleep 0.3; DISPLAY=":${DISP[Caster]}" xdotool keydown w; sleep 4.2; DISPLAY=":${DISP[Caster]}" xdotool keyup w ) &
      mpid=$!
      for i in 1 2 3 4 5; do lsnap Caster "walk-$i"; sleep 0.7; done
      wait $mpid
      montage "$OUT"/lights/walk-*.png -tile 3x -geometry 640x360+4+4 "$OUT/sheet-lights-walk.png" 2>/dev/null || log "WARN: montage walk"
      rcon "kill @e[type=minecraft:zombie]" >/dev/null; rcon "kill @e[type=minecraft:villager]" >/dev/null
      rcon "gamemode spectator Watcher" >/dev/null ;;
    look-down) # F5 back looking steeply down (camera nearly straight above the body)
      prep_skill "$ARG"; CASTER_POSE="$CX $Y $CZ 0 80"; tp Caster $CASTER_POSE; watcher_at 2.4
      perspective 1; rec look-down-f5 Caster; case_sheet look-down-f5 Caster ;;
    move-*) # held movement keys while the rig plays: move-walk (w), move-sprint (ctrl+w), move-strafe (d); fp then F5, with Watcher walking alongside
      mode="${c#move-}"
      case "$mode" in walk) keys="w";; sprint) keys="w ctrl";; strafe) keys="d";; *) keys="w";; esac
      prep_skill "${MOVE_SKILL:-$ARG}"
      for pv in 0 1; do
        perspective $pv; tp Caster $CASTER_POSE; watcher_at 2.0
        ( sleep 0.3; for k in $keys; do DISPLAY=":${DISP[Caster]}" xdotool keydown $k; done
          sleep "${MOVE_HOLD:-3}"; for k in $keys; do DISPLAY=":${DISP[Caster]}" xdotool keyup $k; done ) &
        mpid=$!
        rec "$c-p$pv" Caster Watcher; wait $mpid
        case_sheet "$c-p$pv" Caster Watcher
      done ;;
    *) log "unknown case $c" ;;
  esac
done
perspective 0
log "done: $OUT"
