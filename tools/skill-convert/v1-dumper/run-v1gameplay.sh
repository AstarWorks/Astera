#!/bin/bash
# usage: run-v1gameplay.sh <ids csv> <out dir>
cd /tmp/claude-1000/v1ref || exit 1
export ASTERA_DUMP_IDS="$1"
export ASTERA_DUMP_OUT="$2"
timeout 3400 ./gradlew :tools:game-sim:test --tests '*V1GameplayDumpTest*' --offline -q -i > /tmp/claude-1000/v1g.log 2>&1
echo EXIT=$? >> /tmp/claude-1000/v1g.log
