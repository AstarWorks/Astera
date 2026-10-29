#!/bin/sh
# G1: shortens the timers of the baked content so the e2e playthrough suites finish in CI minutes
# (queue 10s -> 3s, match countdown 10s -> 5s, respawn 5s -> 2s, annihilation build/engage 60s/10min -> 5s/10s,
# pillar HP 500/800 -> 150, Norma layer 10 min -> 150s with 3 levels, star generator period 30s -> 3s). Run by docker/Dockerfile only for
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

# MODE-05/06: every siege lane has its own full-size stage (siege-isles-<v>). The e2e image must not build four
# production-size arenas, so each variant id becomes a copy of the compact debug siege stage (stage-local; world slots keep
# the copies apart). Their star generators are patched onto the compact landmarks below.
for v in tnt wool jewelry mini-tnt; do
  test -f "$root/stages/siege-isles-$v.yaml"
  sed -e "s/^id: debug-siege-isles$/id: siege-isles-$v/" "$root/stages/debug-siege-isles.yaml" > "$root/stages/siege-isles-$v.yaml"
  grep -q "^id: siege-isles-$v$" "$root/stages/siege-isles-$v.yaml" || { echo "e2e-tune: siege-isles-$v was not generated" >&2; exit 1; }
  patch "generators/siege-$v-center-east.yaml" 's/^position:.*/position: { x: 12.5, y: 65.0, z: 0.5 }/' '^position:'
  patch "generators/siege-$v-center-west.yaml" 's/^position:.*/position: { x: -11.5, y: 65.0, z: 0.5 }/' '^position:'
  patch "generators/siege-$v-north.yaml" 's/^position:.*/position: { x: 0.5, y: 65.0, z: -55.5 }/' '^position:'
  patch "generators/siege-$v-south.yaml" 's/^position:.*/position: { x: 0.5, y: 65.0, z: 56.5 }/' '^position:'
done

# Use the preserved compact fixtures only in the E2E image. Keep production IDs
# so queue assignments, mode references, generator IDs and existing protocol assertions agree.
for stage in example-arena siege-isles annihilation-arena pve-proving-grounds; do
  source="$root/stages/debug-$stage.yaml"
  test -f "$source"
  sed -e "s/^id: debug-$stage$/id: $stage/" "$source" > "$root/stages/$stage.yaml"
  rm "$source"
done
# The production siege-isles base generators sit on the full-size team islands (x +-101.5/102.5); the compact debug fixture keeps its red/blue
# islands at r22 around (-68,0)/(68,0), so move the base ore generators back onto them (stage-local, same z rows as production).
for g in "$root"/generators/siege-isles*-base-*.yaml; do
  n="generators/$(basename "$g")"
  case "$n" in
    *-base-red-iron.yaml) pos='{ x: -81.5, y: 65.0, z: -6.0 }' ;;
    *-base-red-gold.yaml) pos='{ x: -81.5, y: 65.0, z: 7.0 }' ;;
    *-base-blue-iron.yaml) pos='{ x: 82.5, y: 65.0, z: -6.0 }' ;;
    *-base-blue-gold.yaml) pos='{ x: 82.5, y: 65.0, z: 7.0 }' ;;
    *) echo "e2e-tune: unexpected base generator $n" >&2; exit 1 ;;
  esac
  patch "$n" "s/^position:.*/position: $pos/" '^position:'
done
# Compact Norma fixture generator coordinates (the production positions are route outposts).
patch generators/arena-east.yaml 's/^position:.*/position: { x: 12.5, y: 65.0, z: 0.5 }/' '^position:'
patch generators/arena-west.yaml 's/^position:.*/position: { x: -11.5, y: 65.0, z: 0.5 }/' '^position:'
patch generators/arena-north.yaml 's/^position:.*/position: { x: 0.5, y: 65.0, z: -61.5 }/' '^position:'
patch generators/arena-south.yaml 's/^position:.*/position: { x: 0.5, y: 65.0, z: 62.5 }/' '^position:'

# Same for the siege-isles star generators: the compact debug stage has a centre island at (0,0) and outposts at z -56/56 (stage-local).
patch generators/siege-center-east.yaml 's/^position:.*/position: { x: 12.5, y: 65.0, z: 0.5 }/' '^position:'
patch generators/siege-center-west.yaml 's/^position:.*/position: { x: -11.5, y: 65.0, z: 0.5 }/' '^position:'
patch generators/siege-north.yaml 's/^position:.*/position: { x: 0.5, y: 65.0, z: -55.5 }/' '^position:'
patch generators/siege-south.yaml 's/^position:.*/position: { x: 0.5, y: 65.0, z: 56.5 }/' '^position:'
patch matchmaking/queue.yaml 's/^queue_countdown_seconds:.*/queue_countdown_seconds: 3/' '^queue_countdown_seconds:'
patch matchmaking/queue.yaml 's/^match_countdown_seconds:.*/match_countdown_seconds: 5/' '^match_countdown_seconds:'

for mode in annihilation carry-tnt; do
  patch "game-modes/$mode.yaml" 's/^(  respawn_delay_seconds:).*/\1 "2"/' '^  respawn_delay_seconds:'
  patch "game-modes/$mode.yaml" 's/^(  death_cam_seconds:).*/\1 "1"/' '^  death_cam_seconds:'
  patch "game-modes/$mode.yaml" 's/^(  spawn_protection_seconds:).*/\1 "1"/' '^  spawn_protection_seconds:'
done

for k in respawn_delay_seconds:2 death_cam_seconds:1 spawn_protection_seconds:1; do
  patch norma-layer/layer.yaml "s/^(  ${k%%:*}:).*/\\1 \"${k##*:}\"/" "^  ${k%%:*}:"
done
# The Norma layer's time limit (shared default in norma-layer/layer.yaml; annihilation keeps its own clock = 0).
patch norma-layer/layer.yaml 's/^(  time_limit_seconds:).*/\1 "150"/' '^  time_limit_seconds:'
# Siege modes keep their own 20 minute limit (they override the layer): the siege playthroughs must not be cut short.
# Except carry-tnt: with the Norma level win gone nothing but a delivery or the clock ends it, and the bot-vs-bot suite (bots fight, they
# do not carry TNT) needs the clock to end the match. The delivery playthroughs finish in about 4 minutes.
patch game-modes/carry-tnt.yaml 's/^(  time_limit_seconds:).*/\1 "420"/' '^  time_limit_seconds:'

patch game-modes/annihilation.yaml 's/^(  pillar_hp:).*/\1 "150"/' '^  pillar_hp:'
patch game-modes/annihilation.yaml 's/^(  pillar_hp_tier2:).*/\1 "150"/' '^  pillar_hp_tier2:'
patch game-modes/annihilation.yaml 's/^(  pillar_hp_tier3:).*/\1 "150"/' '^  pillar_hp_tier3:'
# MODE-A1/A2: the e2e bots shoot pillars from anywhere with a plain weapon, so keep the pre-A1 flat damage: weapons count full, no distance falloff,
# and a bare-hand break lands the old 25.
patch game-modes/annihilation.yaml 's/^(  pillar_break_damage:).*/\1 "25"/' '^  pillar_break_damage:'
patch game-modes/annihilation.yaml 's/^(  pillar_weapon_mult:).*/\1 "1.0"/' '^  pillar_weapon_mult:'
patch game-modes/annihilation.yaml 's/^(  pillar_falloff_near:).*/\1 "200.0"/' '^  pillar_falloff_near:'
patch game-modes/annihilation.yaml 's/^(  pillar_falloff_far:).*/\1 "400.0"/' '^  pillar_falloff_far:'
patch game-modes/annihilation.yaml 's/^(  build_phase_ticks:).*/\1 "100"/' '^  build_phase_ticks:'
patch game-modes/annihilation.yaml 's/^(  engage_phase_ticks:).*/\1 "200"/' '^  engage_phase_ticks:'
patch game-modes/annihilation.yaml 's/^(  sudden_death_ticks:).*/\1 "600"/' '^  sudden_death_ticks:'
# 150 = 60 x 2.5: the HP-100 rebalance made ranged damage x2.5 (60e5669a5), so the pillar keeps the same number of pistol hits.
# The tier-3 pillars carry their own HP in the stage.
patch stages/annihilation-arena.yaml 's/^(    hp:) 800$/\1 150/' '^    hp: 800$'

# carry-tnt (Rainmaker): one trip to the enemy goal wins, with a shield and checkpoints; shield 40 HP (4 s alone).
patch game-modes/carry-tnt.yaml 's/^(  shield_hp:).*/\1 "40"/' '^  shield_hp:'
# capture-wool (Splat Zones): the count drops per second while one team owns every zone; 5 per second = a 20 s hold for the suite.
patch game-modes/capture-wool.yaml 's/^(  count_per_second:).*/\1 "5"/' '^  count_per_second:'

# carry-mini-tnt: the bot needs four laps over the compact stage to pool 10 pieces, so the pickup spots regrow in 3 s instead of 15 s.
patch game-modes/carry-mini-tnt.yaml 's/^(  respawn_seconds:).*/\1 "3"/' '^  respawn_seconds:'
# The suite collects clams for about 200 s and only the clock ends the match (no power TNT from one bot): 900 s -> 300 s, plus up to 60 s overtime.
patch game-modes/carry-mini-tnt.yaml 's/^(  time_limit_seconds:).*/\1 "300"/' '^  time_limit_seconds:'

# Star generators (in-match stars): 30s -> 3s so a bot standing on a pedestal collects within the suite.
# Mid star waves (a `wave:` block instead of period_seconds): 100s -> 10s period, 10s -> 2s warning, so a wave lands within the suite.
for gf in "$root"/generators/*.yaml; do
  g=$(basename "$gf" .yaml)
  if grep -Eq '^wave:' "$gf"; then
    patch "generators/$g.yaml" 's/^  wave_period_seconds:.*/  wave_period_seconds: 10.0/' '^  wave_period_seconds:'
    patch "generators/$g.yaml" 's/^  wave_warn_seconds:.*/  wave_warn_seconds: 2.0/' '^  wave_warn_seconds:'
  else
    patch "generators/$g.yaml" 's/^period_seconds:.*/period_seconds: 3.0/' '^period_seconds:'
  fi
  patch "generators/$g.yaml" 's/^amount:.*/amount: 1/' '^amount:'
done

# Norma levels (layer.yaml, every mode): 3 levels, small goals so a suite reaches them (star goal 2 then 3, kill goal 1 then 2), and the whole
# carried stack bursts on death.
patch norma-layer/layer.yaml 's/^(  norma_max_level:).*/\1 "3"/' '^  norma_max_level:'
patch norma-layer/layer.yaml 's/^(  norma_stars_goal:).*/\1 "2,3"/' '^  norma_stars_goal:'
patch norma-layer/layer.yaml 's/^(  norma_kills_goal:).*/\1 "1,2"/' '^  norma_kills_goal:'
patch norma-layer/layer.yaml 's/^(  death_drop_ratio:).*/\1 "1.0"/' '^  death_drop_ratio:'
