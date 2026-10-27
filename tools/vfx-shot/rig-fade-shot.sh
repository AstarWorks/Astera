#!/usr/bin/env bash
# rig-fade-shot: verify the skin-rig first-person camera-distance fade (ADR-0284) in a REAL client.
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
OUT="$OUT_ROOT/rig-fade"
TICKS=$((LENGTH + 6))

# rec <viewer> <dir>: record the viewer's display, cast over RCON, extract 20 fps frames starting at the cast.
rec() {
  local viewer="$1" dir="$2" rec="$OUT/.rec.mkv" stamp first off
  rm -f "$rec"
  ffmpeg -nostdin -loglevel info -y -copyts -use_wallclock_as_timestamps 1 -f x11grab -framerate 20 \
    -video_size "$RES" -i ":${DISP[$viewer]}" -t "$(awk -v t=$TICKS 'BEGIN{printf "%.2f", 1.5 + t/20 + 2}')" \
    -vf "showinfo,setpts=PTS-STARTPTS" -c:v libx264 -preset ultrafast -crf 10 -pix_fmt yuv444p -f matroska "$rec" 2>"$rec.log" &
  local fpid=$! i
  for i in $(seq 1 100); do grep -q ' pts:' "$rec.log" 2>/dev/null && break; sleep 0.1; done
  sleep 1.5
  stamp="$(rcon --stamp "astera skill cast $SKILL Caster --force" 2>&1 >/dev/null | awk '/^STAMP/{print $2}')"
  [ -n "$stamp" ] || { kill "$fpid" 2>/dev/null; die "cast failed"; }
  wait "$fpid"
  first="$(grep -m1 -o ' pts:[0-9]*' "$rec.log" | tr -dc '0-9' | awk '{printf "%.6f", $1/1000000}')"
  off="$(awk -v c="$stamp" -v f="$first" 'BEGIN{printf "%.3f", c - f + 0.06 - 0.15}')"
  rm -rf "$dir"; mkdir -p "$dir"
  ffmpeg -nostdin -loglevel error -y -i "$rec" -vf "fps=20:start_time=$off,setpts=N/20/TB" -frames:v "$((TICKS + 3))" \
    -start_number 0 "$dir/frame_%03d.png" || die "frame extraction failed"
  rm -f "$rec" "$rec.log"
  sleep "$(awk -v t=$LENGTH 'BEGIN{printf "%.1f", t/20 + 1.5}')"
  tp Caster "$CX" "$Y" "$CZ" 0 0; sleep 1.5
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
# 1. first person (default perspective)
rec Caster "$OUT/first-person"
# 2. F5 once = third person, camera behind
key Caster F5; sleep 1.5
rec Caster "$OUT/third-person-back"
# 3. a bystander's view of the same rig
rec Watcher "$OUT/bystander"

for d in first-person third-person-back bystander; do
  files=()
  for t in 0 4 8 12 16 20; do
    f="$OUT/$d/$(printf 'frame_%03d.png' "$t")"; [ -f "$f" ] && files+=("$f")
  done
  montage "${files[@]}" -tile 3x -geometry +4+4 "$OUT/sheet-$d.png" 2>/dev/null || log "WARN: montage failed for $d"
done
log "done: $OUT"
