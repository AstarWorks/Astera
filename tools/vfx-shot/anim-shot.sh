#!/usr/bin/env bash
# anim-shot: film one skin-rig animation (ADR-0250, content/animations/<id>.yaml) with a REAL Minecraft client for the
# skill-compare animation review (/anim). Reuses the vfx-shot server / client / arena functions.
#
#   make anim-shot ANIM=wave                          # an emote id, played with `/astera emote wave`
#   make anim-shot ANIM=anim-demo-sword-swing         # a skill id whose `play_animation` runs the motion
#
# Output: build/vfx-shot/anim/<animation-id>/
#   vanilla-{front,side,top}.png   the real body at rest
#   rig-{front,side,top}.png       the rig at its first tick (t+0); equals the vanilla pose when there is no pop
#   play/{front,side}/frame_NNN.png  every tick of the motion; NNN = tick + 3 (3 frames before the trigger), see meta.json playPreTicks
#   blend-in/{front,side}/, blend-out/{front,side}/   slices of play/ around the blend, firstTick in meta.json
#   meta.json                      provenance (branch, commit, capture time, anim, length, resolution, FOV)
# Knobs: ITEM (held item, default bastion-greatsword), D (camera distance, 2.4), REST_TICK (max(2, blend_in_ticks)), plus the vfx-shot ones
# (RES, FOV, SETTLE, CAST_LAG_MS, SHOT_KEEP, SHOT_CACHE). See tools/vfx-shot/README.md.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd -P)"
ROOT="$(cd "$HERE/../.." && pwd -P)"
ARG="${1:-${ANIM:-}}"
[ -n "$ARG" ] || { echo "usage: anim-shot.sh <animation-id | skill-id>   (e.g. wave, anim-demo-sword-swing)" >&2; exit 2; }
set -- # the sourced vfx-shot.sh must not see our argument as its `down` command
[[ "$ARG" =~ ^[a-z0-9][a-z0-9-]{0,63}$ ]] || { echo "bad id: $ARG" >&2; exit 2; }

# What does the id name? An animation (emote) or a skill that plays one.
if [ -f "$ROOT/content/animations/$ARG.yaml" ]; then
  ANIM_ID="$ARG"; TRIGGER_KIND=emote; TRIGGER_SKILL=""
elif [ -f "$ROOT/content/skills/$ARG.yaml" ]; then
  ANIM_ID="$(sed -nE 's/^[[:space:]]*animation:[[:space:]]*"?([a-z0-9-]+)"?.*/\1/p' "$ROOT/content/skills/$ARG.yaml" | head -1)"
  [ -n "$ANIM_ID" ] || { echo "skill $ARG has no play_animation 'animation:'" >&2; exit 2; }
  TRIGGER_KIND=skill; TRIGGER_SKILL="$ARG"
else
  echo "no content/animations/$ARG.yaml and no content/skills/$ARG.yaml" >&2; exit 2
fi
ANIM_FILE="$ROOT/content/animations/$ANIM_ID.yaml"
[ -f "$ANIM_FILE" ] || { echo "$ANIM_FILE missing" >&2; exit 2; }
yaml_num() { sed -nE "s/^$1:[[:space:]]*([0-9]+).*/\1/p" "$ANIM_FILE" | head -1; }
yaml_flag() { sed -nE "s/^$1:[[:space:]]*(true|false).*/\1/p" "$ANIM_FILE" | head -1; }
LENGTH="$(yaml_num length_ticks)"; BLEND_IN="$(yaml_num blend_in_ticks)"; BLEND_OUT="$(yaml_num blend_out_ticks)"
LOOP="$(yaml_flag loop)"; LOCK="$(yaml_flag lock_movement)"
LENGTH="${LENGTH:-0}"; BLEND_IN="${BLEND_IN:-0}"; BLEND_OUT="${BLEND_OUT:-0}"; LOOP="${LOOP:-false}"; LOCK="${LOCK:-false}"
[ "$LENGTH" -gt 0 ] || { echo "cannot read length_ticks from $ANIM_FILE" >&2; exit 2; }

SKILL="${TRIGGER_SKILL:-$ANIM_ID}"     # SKILL only names the run (SHOT_ID) here; it is not cast for emotes
ITEM="${ITEM:-bastion-greatsword}"
VIEW=side                              # FOV 90, same defaults as the side view
VFX_SHOT_SOURCED=1
# shellcheck source=vfx-shot.sh
. "$HERE/vfx-shot.sh"

PRE=3                                  # frames kept before the trigger (tick -3..-1)
D="${D:-2.4}"                          # camera distance from the caster
REST_TICK="${REST_TICK:-$((BLEND_IN > 2 ? BLEND_IN : 2))}"   # first tick the rig is surely drawn: blend-in length, at least 2 (client lag ~1-2 ticks)
OUT="$OUT_ROOT/anim/$ANIM_ID"
if [ "$TRIGGER_KIND" = skill ]; then TRIGGER_CMD="astera skill cast $TRIGGER_SKILL Caster --force"
else TRIGGER_CMD="execute as Caster run astera emote $ANIM_ID"; fi
if [ "$LOOP" = true ]; then PLAY_TICKS="$LENGTH"; else PLAY_TICKS=$((LENGTH + BLEND_OUT + 4)); fi
RUN_SECONDS=$(awk -v t="$((LENGTH + BLEND_OUT))" 'BEGIN{printf "%.1f", t/20 + 1.5}')   # time for the motion + blend-out to end

grab() { ffmpeg -nostdin -loglevel error -y -f x11grab -video_size "$RES" -i ":${DISP[Watcher]}" -frames:v 1 "$1"; }

# rec <dir> <ticks> <rcon command>: record the Watcher, send the command over RCON, keep PRE frames before it and ticks+1 from it.
# Frame i = tick i-PRE, synced on the rcon wall-clock stamp (as vfx-shot film() does).
rec() {
  local dir="$1" ticks="$2" cmd="$3" rec="$OUT/.rec.mkv" stamp first off t_total
  t_total="$(awk -v t="$ticks" -v p="$PRE" -v r="$PRE_ROLL" 'BEGIN{printf "%.2f", r + (t + p)/20 + 2.0}')"
  rm -f "$rec"
  ffmpeg -nostdin -loglevel info -y -copyts -use_wallclock_as_timestamps 1 -f x11grab -framerate 20 \
    -video_size "$RES" -i ":${DISP[Watcher]}" -t "$t_total" -vf "showinfo,setpts=PTS-STARTPTS" \
    -c:v libx264 -preset ultrafast -crf 10 -pix_fmt yuv444p -f matroska "$rec" 2>"$rec.log" &
  local fpid=$! i
  for i in $(seq 1 100); do grep -q ' pts:' "$rec.log" 2>/dev/null && break; sleep 0.1; done
  sleep "$PRE_ROLL"
  stamp="$(rcon --stamp "$cmd" 2>&1 >/dev/null | awk '/^STAMP/{print $2}')"
  [ -n "$stamp" ] || { kill "$fpid" 2>/dev/null; die "command failed: $cmd"; }
  wait "$fpid"
  [ -s "$rec" ] || die "recording failed"
  first="$(grep -m1 -o ' pts:[0-9]*' "$rec.log" | tr -dc '0-9' | awk '{printf "%.6f", $1/1000000}')"
  [ -n "$first" ] || die "no first frame time in $rec.log"
  off="$(awk -v c="$stamp" -v f="$first" -v l="$CAST_LAG_MS" -v p="$PRE" 'BEGIN{printf "%.3f", c - f + l/1000.0 - p/20.0}')"
  rm -rf "$dir"; mkdir -p "$dir"
  ffmpeg -nostdin -loglevel error -y -i "$rec" -vf "fps=20:start_time=$off,setpts=N/20/TB" \
    -frames:v "$((PRE + ticks + 1))" -start_number 0 "$dir/frame_%03d.png" || die "frame extraction failed"
  rm -f "$rec" "$rec.log"
}

# camera: front = in front of the caster looking back, side = 90 degrees, top = 3 blocks above looking down
cam() { # front|side|top
  local a x z yaw
  case "$1" in
    front) a=0 ;;
    side) a=-90 ;;
    top) tp Watcher "$CX" "$((Y + 3))" "$CZ" 0 90; sleep 1.5; return ;;
  esac
  read -r x z yaw <<<"$(awk -v cx="$CX" -v cz="$CZ" -v d="$D" -v a="$a" 'BEGIN{r=a*3.14159265/180; yaw=180-a; while(yaw>180)yaw-=360; printf "%.3f %.3f %.1f", cx + d*sin(r), cz + d*cos(r), yaw}')"
  tp Watcher "$x" "$(awk -v y="$Y" 'BEGIN{print y-0.5}')" "$z" "$yaw" 0
  sleep 1.3
}
reset() { # caster back to the middle, motion ended
  [ "$LOOP" = true ] && rcon "execute as Caster run astera emote stop" >/dev/null
  sleep "$RUN_SECONDS"
  tp Caster "$CX" "$Y" "$CZ" 0 0
  sleep 1.5
}

# ---------------------------------------------------------------- main
trap cleanup EXIT
trap 'exit 130' INT TERM
acquire_slot
command -v ffmpeg >/dev/null && command -v xdotool >/dev/null && command -v Xvfb >/dev/null \
  && command -v montage >/dev/null || die "need ffmpeg, xdotool, Xvfb and ImageMagick (montage)"
ensure_client_install
boot_server
if [ "$TRIGGER_KIND" = skill ] && ! grep -qw -- "$TRIGGER_SKILL" <<<"$(rcon "astera skill list")"; then
  die "skill '$TRIGGER_SKILL' is not registered on the server (see: astera skill list)"
fi
mkdir -p "$OUT"

RES_FILM="$RES"
setup_world
build_arena
start_client Watcher "${RES%x*}" "${RES#*x}"; wait_client_ready Watcher
start_client Caster 480 270; wait_client_ready Caster
sleep "$JOIN_SETTLE"
give_item Caster
rcon "gamemode spectator Watcher" >/dev/null
rcon "gamemode survival Caster" >/dev/null
for tries in 1 2 3 4 5; do
  tp Caster "$CX" "$Y" "$CZ" 0 0; tp Watcher "$CX" "$Y" "$CZ" 0 0; sleep "$SETTLE"
  in_arena Caster && in_arena Watcher && break
  log "players were moved out of the arena (attempt $tries), placing again"
done
in_arena Caster && in_arena Watcher || die "could not keep the players in the overworld arena"
rcon "kill @e[type=minecraft:item]" >/dev/null
key Watcher 1
[ "$HIDE_HUD" = 1 ] && key Watcher F1
DISPLAY=":${DISP[Watcher]}" xdotool mousemove 640 360 2>/dev/null || true

# 1. rest comparison: the real body, then the rig at tick REST_TICK from each camera
tp Caster "$CX" "$Y" "$CZ" 0 0; sleep 1.5
for v in front side top; do cam "$v"; grab "$OUT/vanilla-$v.png"; done
for v in front side top; do
  cam "$v"
  rec "$OUT/.rest-$v" "$((REST_TICK + 1))" "$TRIGGER_CMD"
  cp "$OUT/.rest-$v/$(printf 'frame_%03d.png' "$((PRE + REST_TICK))")" "$OUT/rig-$v.png"
  rm -rf "$OUT/.rest-$v"
  reset
done

# 2. the whole motion from front and side (frame i = tick i-PRE)
for v in front side; do
  cam "$v"
  rec "$OUT/play/$v" "$PLAY_TICKS" "$TRIGGER_CMD"
  reset
done

# 3. blend strips: slices of play/ (non-loop motions end by themselves). A loop needs an explicit stop to blend out.
slice() { # kind view firstTick count
  local kind="$1" v="$2" first="$3" n="$4" i src
  rm -rf "$OUT/$kind/$v"; mkdir -p "$OUT/$kind/$v"
  for ((i = 0; i < n; i += 1)); do
    src="$OUT/play/$v/$(printf 'frame_%03d.png' "$((first + PRE + i))")"
    [ -f "$src" ] && cp "$src" "$OUT/$kind/$v/$(printf 'frame_%03d.png' "$i")"
  done
}
IN_FIRST=-"$PRE"; IN_N=$((PRE + BLEND_IN + 3))
for v in front side; do slice blend-in "$v" "$IN_FIRST" "$IN_N"; done
if [ "$LOOP" = true ]; then
  OUT_FIRST=-"$PRE"; OUT_N=$((PRE + BLEND_OUT + 3)); OUT_REL=stop
  for v in front side; do
    cam "$v"
    rcon "$TRIGGER_CMD" >/dev/null; sleep 1.5
    rec "$OUT/blend-out/$v" "$((BLEND_OUT + 2))" "execute as Caster run astera emote stop"
    reset
  done
else
  OUT_FIRST=$((LENGTH - 2)); OUT_N=$((BLEND_OUT + 6)); OUT_REL=start
  for v in front side; do slice blend-out "$v" "$OUT_FIRST" "$OUT_N"; done
fi

# 4. web previews (PNG stays the measurement source) and provenance
for d in "$OUT"/play/* "$OUT"/blend-in/* "$OUT"/blend-out/*; do
  [ -d "$d" ] || continue
  ffmpeg -nostdin -loglevel error -y -i "$d/frame_%03d.png" -c:v libwebp -quality 85 -compression_level 4 -threads 2 \
    -start_number 0 "$d/frame_%03d.webp" || log "WARN: web preview failed in $d; serving PNG"
done
python3 -I - "$ROOT" "$OUT" "$ANIM_ID" "$TRIGGER_KIND" "${TRIGGER_SKILL:-}" "$LENGTH" "$LOOP" "$LOCK" "$BLEND_IN" "$BLEND_OUT" \
  "$PRE" "$PLAY_TICKS" "$IN_FIRST" "$IN_N" "$OUT_FIRST" "$OUT_N" "${OUT_REL:-start}" "$FOV" "$RES" "$REST_TICK" <<'EOF'
import datetime, json, subprocess, sys
(root, out, anim, kind, skill, length, loop, lock, bin_, bout, pre, play, in_first, in_n, out_first, out_n, out_rel, fov, res, rest) = sys.argv[1:]
def git(*a):
    try: return subprocess.check_output(["git", *a], cwd=root, text=True).strip()
    except Exception: return None
meta = {
    "view": "anim", "anim": anim, "fps": 20, "frames": int(play) + int(pre) + 1,
    "trigger": {"kind": kind, **({"skill": skill} if skill else {})},
    "lengthTicks": int(length), "loop": loop == "true", "lockMovement": lock == "true",
    "blendInTicks": int(bin_), "blendOutTicks": int(bout),
    "playPreTicks": int(pre), "restTick": int(rest),
    "blendIn": {"firstTick": int(in_first), "frames": int(in_n)},
    "blendOut": {"firstTick": int(out_first), "frames": int(out_n), "relativeTo": out_rel},
    "fov": float(fov), "fovMode": "fixed", "resolution": res,
    "capturedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "implementationCommit": git("rev-parse", "HEAD"), "branch": git("rev-parse", "--abbrev-ref", "HEAD"),
}
open(out + "/meta.json", "w").write(json.dumps(meta, indent=2) + "\n")
EOF
log "done: $OUT"
