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

patch matchmaking/queue.yaml 's/^queue_countdown_seconds:.*/queue_countdown_seconds: 3/' '^queue_countdown_seconds:'
patch matchmaking/queue.yaml 's/^match_countdown_seconds:.*/match_countdown_seconds: 5/' '^match_countdown_seconds:'

for mode in skirmish annihilation break-core; do
  patch "game-modes/$mode.yaml" 's/^(  respawn_delay_seconds:).*/\1 "2"/' '^  respawn_delay_seconds:'
  patch "game-modes/$mode.yaml" 's/^(  death_cam_seconds:).*/\1 "1"/' '^  death_cam_seconds:'
  patch "game-modes/$mode.yaml" 's/^(  spawn_protection_seconds:).*/\1 "1"/' '^  spawn_protection_seconds:'
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
