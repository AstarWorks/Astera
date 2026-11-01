#!/usr/bin/env bash
# Real-client stills. Camera TSV: name local-x feet-y local-z yaw pitch.
# bash tools/vfx-shot/map-shot.sh cameras.tsv output-dir [stage-id]
set -euo pipefail
CAMERAS="$(realpath "${1:?camera TSV required}")"
DEST="$(realpath -m "${2:?output directory required}")"
STAGE="${3:-annihilation-arena}"
set --
SKILL=map-shot VIEW=side ITEM=none RES=1920x1080
VFX_SHOT_SOURCED=1
. "$(dirname "${BASH_SOURCE[0]}")/vfx-shot.sh"
# The existing helpers intentionally tolerate unsuccessful optional RCON commands.
set +e
mkdir -p "$DEST/after" "$DEST/world"
run_begin
ensure_client_install
boot_server
phase "waiting for $STAGE import clear/paste/verify"
slot=""
for i in $(seq 1 600); do
  docker logs "$SERVER_CT" > "$DEST/server.log" 2>&1
  slot="$(sed -n "s/.*Arena '$STAGE' ready in slot \([0-9]*\):.*/\1/p" "$DEST/server.log" | head -1)"
  grep -q "Arena '$STAGE' paste failed" "$DEST/server.log" && die "stage import failed; see $DEST/server.log"
  [ -z "$slot" ] || break
  sleep 2
done
[ -n "$slot" ] || die "stage $STAGE never became Ready; see $DEST/server.log"
origin_x=$((slot * 2048))
printf 'stage=%s\nslot=%s\norigin_x=%s\norigin_z=0\n' "$STAGE" "$slot" "$origin_x" > "$DEST/placement.txt"
phase "stage Ready, slot $slot origin ($origin_x,0)"
ensure_client Watcher 1920 1080
sleep "$JOIN_SETTLE"
setup_world
watcher_camera_mode
key Watcher F1
while read -r name x y z yaw pitch; do
  [[ -n "$name" && "$name" != \#* ]] || continue
  [[ "$name" =~ ^[a-z0-9-]+$ ]] || die "invalid capture name $name"
  wx="$(awk -v x="$x" -v o="$origin_x" 'BEGIN {print x+o}')"
  tp Watcher "$wx" "$y" "$z" "$yaw" "$pitch"
  sleep "${MAP_SETTLE:-15}"
  rcon "data get entity Watcher Pos" >> "$DEST/cameras.log"
  rcon "data get entity Watcher Rotation" >> "$DEST/cameras.log"
  ffmpeg -nostdin -loglevel error -y -f x11grab -video_size 1920x1080 \
    -i ":${DISP[Watcher]}" -frames:v 1 "$DEST/after/$name.png" || die "capture failed: $name"
  phase "captured $name"
done < "$CAMERAS"
# Flush, stop, then copy the level for a consistent read-only scan.
rcon "save-all flush" > "$DEST/save.log" || die "save failed"
compose stop e2e >> "$DEST/save.log" 2>&1 || die "server stop failed"
docker cp "$SERVER_CT:/data/world/." "$DEST/world/" || die "world snapshot failed"
docker logs "$SERVER_CT" > "$DEST/server.log" 2>&1
cp "$PHASE_LOG" "$DEST/phases.log"
cp "$CAMERAS" "$DEST/cameras.tsv"
git -C "$ROOT" rev-parse HEAD > "$DEST/revision.txt"
phase "done: $DEST (clean slot; no legacy fixture seeded)"
