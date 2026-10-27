#!/usr/bin/env bash
# MODE-ARS7: real-client placement and contrast review, isolated shot stack only.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd -P)"
SKILL=anim-demo-sword-swing ITEM=packed-clay-block VIEW=caster VFX_SHOT_SOURCED=1
RES=1920x720 FOV=90 SHOT_REAP_SCOPE=self
source "$ROOT/tools/vfx-shot/vfx-shot.sh"
KEEP="${PLACED_KEEP:-0}"
OUT=/tmp/claude-1000/codex/placed-block-look/shots
cell_center() { awk -v n="$1" 'BEGIN { printf "%.1f", n + 0.5 }'; }
run_begin
ensure_client_install
boot_server
mkdir -p "$OUT"
: > "$OUT/placement.txt"
: > "$OUT/failed-state.txt"
CLIENT_STARTED=0
ensure_client Caster "${RES%x*}" "${RES#*x}"
if [ "$CLIENT_STARTED" = 0 ] && [ -f "$STATE/placed-hidden" ]; then key Caster F1; fi
rm -f "$STATE/placed-hidden"
rcon 'execute as Caster run astera queue join pve-waves' > "$OUT/queue.txt"
active=0
for _ in $(seq 1 100); do
  rcon "astera match list" > "$OUT/match.txt"
  if grep -q ACTIVE "$OUT/match.txt"; then active=1; break; fi
  sleep 1
done
[ "$active" = 1 ] || die 'PvE never ACTIVE'
sleep 3
rcon 'data get entity Caster Pos' > "$OUT/player-pos.txt"
rcon 'data get entity Caster Dimension' > "$OUT/player-dimension.txt"
read -r PX PY PZ < <(python3 - "$OUT/player-pos.txt" <<'PY'
import re,sys,math
s=open(sys.argv[1]).read(); m=re.search(r'\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]',s)
assert m,s
print(*(math.floor(float(v)) for v in m.groups()))
PY
)
rcon 'gamemode creative Caster' >/dev/null
rcon 'effect give Caster minecraft:resistance infinite 5 true' >/dev/null
# All edits are inside the active player's arena, relative to that player.
# Row lies 5 blocks ahead, with original blocks and terrain at the same height.
Z=$((PZ+5)); Y=$PY; X0=$((PX-6))
rcon 'time set 6000' >/dev/null
rcon 'weather clear' >/dev/null
rcon 'effect give Caster minecraft:night_vision infinite 0 true' >/dev/null
rcon "execute at Caster run fill $((X0-2)) $Y $((Z-10)) $((X0+14)) $((Y+10)) $((Z+8)) minecraft:air" >/dev/null
rcon "execute at Caster run fill $((X0-1)) $((Y-1)) $((Z-1)) $((X0+13)) $((Y-1)) $((Z+1)) minecraft:stone" >/dev/null
names=(barricade hardened-soil packed-clay reinforced-planks reinforced-wall sandbags stone-bulwark tempered-glass obsidian-plate)
i=0
for name in "${names[@]}"; do
  X=$((X0+i))
  rcon 'clear Caster' >/dev/null
  rcon "astera items give Caster $name-block 2" >/dev/null
  tp Caster "$(cell_center "$X")" "$Y" "$(cell_center "$((Z-2))")" 0 35
  key Caster 1
  sleep 0.6
  # Aim at the front face of the support; place on its top via a steeper ray.
  tp Caster "$(cell_center "$X")" "$Y" "$(cell_center "$((Z-1))")" 0 65
  sleep 0.4
  DISPLAY=":${DISP[Caster]}" xdotool click 3
  sleep 0.5
  state="minecraft:note_block[instrument=harp,note=$((i+1)),powered=false]"
  reply="$(rcon "execute at Caster if block $X $Y $Z $state")"
  echo "$name: $reply" >> "$OUT/placement.txt"
  # Fail on a misplaced click; never substitute command-placed blocks for placement evidence.
  if ! grep -qi passed <<<"$reply"; then
    rcon 'data get entity Caster SelectedItem' > "$OUT/failed-held.txt"
    for material in note_block rooted_dirt stone air mangrove_planks; do
      rcon "execute at Caster if block $X $Y $Z minecraft:$material" | sed "s/^/$material: /" >> "$OUT/failed-state.txt"
    done
    for instrument in harp basedrum bass bell; do
      rcon "execute at Caster if block $X $Y $Z minecraft:note_block[instrument=$instrument]" | sed "s/^/$instrument: /" >> "$OUT/failed-state.txt"
    done
    ffmpeg -nostdin -loglevel error -y -f x11grab -video_size "$RES" -i ":${DISP[Caster]}" -frames:v 1 "$OUT/failed.png"
    die "real-client placement missed $name ($reply)"
  fi
  rcon "execute at Caster run setblock $X $((Y-1)) $Z minecraft:gold_block" >/dev/null
  sleep 0.2
  updated="$(rcon "execute at Caster if block $X $Y $Z $state")"
  echo "$name neighbor update: $updated" >> "$OUT/placement.txt"
  grep -qi passed <<<"$updated" || die "neighbor changed $name"
  rcon "execute at Caster run setblock $((X+1)) $Y $Z minecraft:redstone_block" >/dev/null
  sleep 0.2
  powered="$(rcon "execute at Caster if block $X $Y $Z $state")"
  echo "$name redstone update: $powered" >> "$OUT/placement.txt"
  grep -qi passed <<<"$powered" || die "redstone changed $name"
  rcon "execute at Caster run setblock $((X+1)) $Y $Z minecraft:air" >/dev/null
  i=$((i+1))
done
for material in dirt stone oak_planks glass; do
  rcon "execute at Caster run setblock $((X0+i)) $Y $Z minecraft:$material" >/dev/null
  i=$((i+1))
done
rcon 'clear Caster' >/dev/null
key Caster F1
touch "$STATE/placed-hidden"
for distance in 3 8; do
  pitch=8; [ "$distance" = 3 ] && pitch=20
  tp Caster "$(cell_center "$((X0+6))")" "$Y" "$(cell_center "$((Z-distance))")" 0 "$pitch"
  sleep 3
  ffmpeg -nostdin -loglevel error -y -f x11grab -video_size "$RES" -i ":${DISP[Caster]}" -frames:v 1 "$OUT/row-${distance}m.png"
done
montage -font DejaVu-Sans -label '3m: nine placed blocks + dirt / stone / oak / glass' "$OUT/row-3m.png" -label '8m: same row' "$OUT/row-8m.png" -tile 1x2 -geometry 1920x720+4+16 "$OUT/sheet.png"
printf '%s\n' "$(git -C "$ROOT" rev-parse HEAD)" > "$OUT/implementation.txt"
cp "$STATE/deployed.json" "$OUT/deployed.json"
python3 - "$OUT" "$ROOT" "$X0" "$Y" "$Z" <<'PYMETA'
import json, pathlib, subprocess, sys, datetime
out, root, x, y, z = sys.argv[1:]
meta = dict(capturedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
    implementationCommit=subprocess.check_output(['git','-C',root,'rev-parse','HEAD'],text=True).strip(),
    contentRevision=subprocess.check_output(['git','-C',root,'rev-parse','HEAD:content'],text=True).strip(),
    dirty=bool(subprocess.check_output(['git','-C',root,'status','--porcelain'],text=True)),
    rowOrigin=[int(x),int(y),int(z)], distances=[3,8], placement='real client native item placement',
    match='pve-waves ACTIVE', lighting='daytime + night vision', screenOrder='glass, oak, stone, dirt, obsidian-plate, tempered-glass, stone-bulwark, sandbags, reinforced-wall, reinforced-planks, packed-clay, hardened-soil, barricade')
pathlib.Path(out,'meta.json').write_text(json.dumps(meta,indent=2)+'\n')
PYMETA
