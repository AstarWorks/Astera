#!/usr/bin/env bash
# rig-fade-shot: verify the skin-rig first-person camera-distance fade (ADR-0285) in a REAL client.
# Films the same animation from the CASTER's own client in first person and in F5 (third person, back), plus a bystander
# (Watcher) 2.4 blocks off the side. Output: build/vfx-shot/rig-fade/{first-person,third-person-back,bystander}/frame_NNN.png
# and sheet-*.png contact sheets.   make rig-fade-shot [ANIM=anim-demo-sword-swing]
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd -P)"
ROOT="$(cd "$HERE/../.." && pwd -P)"
ARG="${1:-${ANIM:-anim-demo-sword-swing}}"
set --
ANIM_ID="$(sed -nE 's/^[[:space:]]*animation:[[:space:]]*"?([a-z0-9-]+)"?.*/\1/p' "$ROOT/content/skills/$ARG.yaml" | head -1)"
[ -n "$ANIM_ID" ] || { echo "skill $ARG has no animation" >&2; exit 2; }
ANIM_FILE="$ROOT/content/animations/$ANIM_ID.yaml"
LENGTH="$(sed -nE 's/^length_ticks:[[:space:]]*([0-9]+).*/\1/p' "$ANIM_FILE" | head -1)"
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
  tp Caster "$CX" "$Y" "$CZ" 0 0; sleep 1.5
}

# sheet <dir> <out.png>: contact sheet, one tile per 2 ticks, labelled with the frame number.
sheet() {
  local d="$1" out="$2" files=() t f
  for t in $(seq 4 2 28); do
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
tp Caster "$CX" "$Y" "$CZ" 0 0; tp Watcher "$CX" "$Y" "$CZ" 0 0; sleep "$SETTLE"
rcon "kill @e[type=minecraft:item]" >/dev/null
key Watcher 1; key Caster 1
key Watcher F1; key Caster F1
DISPLAY=":${DISP[Caster]}" xdotool mousemove 100 100 2>/dev/null || true

# bystander: 2.4 blocks off the caster's side, looking at them (the anim-shot "side" camera)
tp Watcher "$(awk -v cx=$CX 'BEGIN{print cx-2.4}')" "$(awk -v y=$Y 'BEGIN{print y-0.5}')" "$CZ" -90 0; sleep 1.3
# 1. first person (caster) + bystander (watcher) in the same cast
rec fp Caster Watcher
# 2. F5 once = third person, camera behind (vanilla distance 4 blocks)
key Caster F5; sleep 1.5
rec f5 Caster
sheet "$OUT/fp-Caster" "$OUT/sheet-first-person.png"
sheet "$OUT/fp-Watcher" "$OUT/sheet-bystander.png"
sheet "$OUT/f5-Caster" "$OUT/sheet-third-person-back.png"
log "done: $OUT"
