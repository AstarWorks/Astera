#!/bin/sh
# G1: shortens the timers of the baked content so the e2e playthrough suites finish in CI minutes
# (queue 10s -> 3s, match countdown 10s -> 5s, respawn 5s -> 2s, annihilation build/engage 60s/10min -> 5s/10s,
# pillar HP 500/800 -> 60, Norma layer 10 min -> 150s with 3 levels, star generator period 30s -> 3s). Run by docker/Dockerfile only for
# `--build-arg E2E_FAST=1` (docker-compose.e2e.yml); the dev and live images keep the real values.
# Every edit asserts that its pattern is present, so content drift fails the image build instead of
# silently running the suites with production timers.
set -eu
root="$1"

patch() { # <file under root> <sed -E expression> <grep -E pattern that must match>
  f="$root/$1"
  grep -Eq "$3" "$f" || { echo "e2e-tune: $f has no line matching /$3/" >&2; exit 1; }
  sed -i -E "$2" "$f"
}

# MODE-05/06: every siege lane has its own full-size stage (siege-isles-<v>, +800 z each). The e2e image must not build five
# production-size arenas, so each variant id becomes a copy of the compact debug siege stage shifted along z (offsets keep the
# copies apart, same world), with its star generators moved onto the compact landmarks of the shifted copy.
for variant in tnt:800 wool:1600 jewelry:2400 loyal:3200 mini-tnt:4000; do
  v="${variant%%:*}"
  off="${variant##*:}"
  test -f "$root/stages/siege-isles-$v.yaml"
  awk -v off="$off" '{ out = ""; line = $0
    while (match(line, /z: -?[0-9.]+/)) { out = out substr(line, 1, RSTART + 2) (substr(line, RSTART + 3, RLENGTH - 3) + off); line = substr(line, RSTART + RLENGTH) }
    print out line }' "$root/stages/debug-siege-isles.yaml" \
    | sed -e "s/^id: debug-siege-isles$/id: siege-isles-$v/" -e 's/^world: debug-arenas$/world: arenas/' > "$root/stages/siege-isles-$v.yaml"
  grep -q "^id: siege-isles-$v$" "$root/stages/siege-isles-$v.yaml" || { echo "e2e-tune: siege-isles-$v was not generated" >&2; exit 1; }
  patch "generators/siege-$v-center-east.yaml" "s/^position:.*/position: { x: 12.5, y: 65.0, z: $((300 + off)).5 }/" '^position:'
  patch "generators/siege-$v-center-west.yaml" "s/^position:.*/position: { x: -11.5, y: 65.0, z: $((300 + off)).5 }/" '^position:'
  patch "generators/siege-$v-north.yaml" "s/^position:.*/position: { x: 0.5, y: 65.0, z: $((244 + off)).5 }/" '^position:'
  patch "generators/siege-$v-south.yaml" "s/^position:.*/position: { x: 0.5, y: 65.0, z: $((356 + off)).5 }/" '^position:'
done

# Use the preserved compact fixtures only in the E2E image. Keep production IDs and world
# so queue assignments, mode references, generator IDs and existing protocol assertions agree.
for stage in example-arena siege-isles annihilation-arena pve-proving-grounds; do
  source="$root/stages/debug-$stage.yaml"
  test -f "$source"
  sed -e "s/^id: debug-$stage$/id: $stage/" -e 's/^world: debug-arenas$/world: arenas/' "$source" > "$root/stages/$stage.yaml"
  rm "$source"
done
# Compact Norma fixture generator coordinates (the production positions are route outposts).
patch generators/arena-east.yaml 's/^position:.*/position: { x: 12.5, y: 65.0, z: 0.5 }/' '^position:'
patch generators/arena-west.yaml 's/^position:.*/position: { x: -11.5, y: 65.0, z: 0.5 }/' '^position:'
patch generators/arena-north.yaml 's/^position:.*/position: { x: 0.5, y: 65.0, z: -61.5 }/' '^position:'
patch generators/arena-south.yaml 's/^position:.*/position: { x: 0.5, y: 65.0, z: 62.5 }/' '^position:'

# Same for the siege-isles star generators: the compact debug stage has a centre island at (0,300) and outposts at z 244/356.
patch generators/siege-center-east.yaml 's/^position:.*/position: { x: 12.5, y: 65.0, z: 300.5 }/' '^position:'
patch generators/siege-center-west.yaml 's/^position:.*/position: { x: -11.5, y: 65.0, z: 300.5 }/' '^position:'
patch generators/siege-north.yaml 's/^position:.*/position: { x: 0.5, y: 65.0, z: 244.5 }/' '^position:'
patch generators/siege-south.yaml 's/^position:.*/position: { x: 0.5, y: 65.0, z: 356.5 }/' '^position:'
patch matchmaking/queue.yaml 's/^queue_countdown_seconds:.*/queue_countdown_seconds: 3/' '^queue_countdown_seconds:'
patch matchmaking/queue.yaml 's/^match_countdown_seconds:.*/match_countdown_seconds: 5/' '^match_countdown_seconds:'

# skirmish has no timers of its own: it inherits the Norma layer's (patched below).
for mode in annihilation break-core; do
  patch "game-modes/$mode.yaml" 's/^(  respawn_delay_seconds:).*/\1 "2"/' '^  respawn_delay_seconds:'
  patch "game-modes/$mode.yaml" 's/^(  death_cam_seconds:).*/\1 "1"/' '^  death_cam_seconds:'
  patch "game-modes/$mode.yaml" 's/^(  spawn_protection_seconds:).*/\1 "1"/' '^  spawn_protection_seconds:'
done

for k in respawn_delay_seconds:2 death_cam_seconds:1 spawn_protection_seconds:1; do
  patch norma-layer/layer.yaml "s/^(  ${k%%:*}:).*/\\1 \"${k##*:}\"/" "^  ${k%%:*}:"
done
# The Norma layer's time limit (shared default in norma-layer/layer.yaml; annihilation keeps its own clock = 0).
patch norma-layer/layer.yaml 's/^(  time_limit_seconds:).*/\1 "150"/' '^  time_limit_seconds:'
# Siege modes keep their own 20 minute limit (they override the layer): the break-core playthrough must not be cut short.

patch game-modes/annihilation.yaml 's/^(  pillar_hp:).*/\1 "60"/' '^  pillar_hp:'
patch game-modes/annihilation.yaml 's/^(  build_phase_ticks:).*/\1 "100"/' '^  build_phase_ticks:'
patch game-modes/annihilation.yaml 's/^(  engage_phase_ticks:).*/\1 "200"/' '^  engage_phase_ticks:'
patch game-modes/annihilation.yaml 's/^(  sudden_death_ticks:).*/\1 "600"/' '^  sudden_death_ticks:'
# The tier-3 pillars carry their own HP in the stage.
patch stages/annihilation-arena.yaml 's/^(    hp:) 800$/\1 60/' '^    hp: 800$'

# Star generators (in-match stars): 30s -> 3s so a bot standing on a pedestal collects within the suite.
for gf in "$root"/generators/*.yaml; do
  g=$(basename "$gf" .yaml)
  patch "generators/$g.yaml" 's/^period_seconds:.*/period_seconds: 3.0/' '^period_seconds:'
done

# Norma levels (layer.yaml, every mode): 3 levels, small goals so a suite reaches them (star goal 2 then 3, kill goal 1 then 2), and the whole
# carried stack bursts on death.
patch norma-layer/layer.yaml 's/^(  norma_max_level:).*/\1 "3"/' '^  norma_max_level:'
patch norma-layer/layer.yaml 's/^(  norma_stars_goal:).*/\1 "2,3"/' '^  norma_stars_goal:'
patch norma-layer/layer.yaml 's/^(  norma_kills_goal:).*/\1 "1,2"/' '^  norma_kills_goal:'

# Battle Loyal inherits the Norma layer's 150 s limit; the siege-scale fight gets 300 s so bots can cross the islands and meet.
if grep -Eq '^  time_limit_seconds:' "$root/game-modes/battle-loyal.yaml"; then
  patch game-modes/battle-loyal.yaml 's/^(  time_limit_seconds:).*/\1 "300"/' '^  time_limit_seconds:'
else
  patch game-modes/battle-loyal.yaml 's/^default_config:.*/&\n  time_limit_seconds: "300"/' '^default_config:'
fi
