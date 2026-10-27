#!/usr/bin/env bash
# One real inventory hover, using vfx-shot's isolated server/client lifecycle (never the dev stack).
# bash tools/item-art/scripts/tooltip-shot.sh [item-id]
set -uo pipefail
ITEM="${1:-orrery-staff}"
set --
export VIEW=caster GUI_SCALE=2 SHOT_REAP_SCOPE=self SHOT_KEEP=0
export SKILL=tooltip-preview SHOT_TIMEOUT="${SHOT_TIMEOUT:-900}"
SCRIPT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
VFX_SHOT_SOURCED=1 source "$SCRIPT_ROOT/tools/vfx-shot/vfx-shot.sh"
# Queue on the same single slot instead of losing it to consecutive farm jobs between polls.
acquire_slot() {
  exec {SLOT_FD}>"${SHOT_LOCK:-/tmp/claude-1000/astera-vfx-shot.lock}"
  log "waiting for the shared capture slot"
  flock "$SLOT_FD" || die "capture slot lock failed"
  log "shared capture slot acquired"
}
# The VFX overlay blanks ASCII text. Retain the normal font for the tooltip capture.
eval "$(declare -f write_client_files | sed '1s/write_client_files/write_vfx_client_files/')"
write_client_files() {
  write_vfx_client_files "$@"
  zip -dq "$CLIENTS/$1/resourcepacks/astera-shot-fix.zip" assets/minecraft/font/default.json
}
run_begin
ensure_client_install
declare -A PACK_RELOADS_BEFORE
for name in Watcher Caster Target; do PACK_RELOADS_BEFORE[$name]="$(pack_reloads "$name")"; done
boot_server
setup_world
build_arena
start_client Caster 1280 720
wait_client_ready Caster
rcon "gamemode survival Caster" >/dev/null
tp Caster "$CX" "$Y" "$CZ" 0 0
sleep "$SETTLE"
give_item Caster
key Caster e
sleep 2
DISPLAY=":${DISP[Caster]}" xdotool mousemove 496 494
sleep 2
mkdir -p "$ROOT/build/tooltip-art"
ffmpeg -nostdin -loglevel error -y -f x11grab -video_size 1280x720 -i ":${DISP[Caster]}" -frames:v 1 "$ROOT/build/tooltip-art/hover-$ITEM.png" || die "hover capture failed"
rcon "data get entity Caster Inventory[{Slot:0b}].components.\"minecraft:tooltip_style\"" > "$ROOT/build/tooltip-art/hover-$ITEM-component.txt"
cp "$STATE/deployed.json" "$ROOT/build/tooltip-art/hover-$ITEM-meta.json"
