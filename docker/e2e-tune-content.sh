#!/bin/sh
# G1: shortens the timers of the baked content so the e2e playthrough suites finish in CI minutes
# (queue 10s -> 3s, match countdown 10s -> 5s, respawn 5s -> 2s, annihilation build/engage 60s/10min -> 5s/10s,
# pillar HP 500/800 -> 60, norma 10 kills / 10 min -> 3 kills / 150s). Run by docker/Dockerfile only for
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

for mode in norma annihilation break-core; do
  patch "game-modes/$mode.yaml" 's/^(  respawn_delay_seconds:).*/\1 "2"/' '^  respawn_delay_seconds:'
  patch "game-modes/$mode.yaml" 's/^(  death_cam_seconds:).*/\1 "1"/' '^  death_cam_seconds:'
  patch "game-modes/$mode.yaml" 's/^(  spawn_protection_seconds:).*/\1 "1"/' '^  spawn_protection_seconds:'
done

patch game-modes/norma.yaml 's/^(  score_threshold:).*/\1 "3"/' '^  score_threshold:'
patch game-modes/norma.yaml 's/^(  time_limit_seconds:).*/\1 "150"/' '^  time_limit_seconds:'

patch game-modes/annihilation.yaml 's/^(  pillar_hp:).*/\1 "60"/' '^  pillar_hp:'
patch game-modes/annihilation.yaml 's/^(  build_phase_ticks:).*/\1 "100"/' '^  build_phase_ticks:'
patch game-modes/annihilation.yaml 's/^(  engage_phase_ticks:).*/\1 "200"/' '^  engage_phase_ticks:'
patch game-modes/annihilation.yaml 's/^(  sudden_death_ticks:).*/\1 "600"/' '^  sudden_death_ticks:'
# The tier-3 pillars carry their own HP in the stage.
patch stages/annihilation-arena.yaml 's/^(    hp:) 800$/\1 60/' '^    hp: 800$'
