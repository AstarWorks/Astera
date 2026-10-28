#!/bin/bash
cd /tmp/claude-1000/v1ref || exit 1
export ASTERA_DUMP_IDS="$(cat /tmp/claude-1000/ids-both.csv)"
export ASTERA_DUMP_OUT=/tmp/claude-1000/skill-engine/audit/vanilla/v1
timeout 3000 ./gradlew :tools:game-sim:test --tests '*V1VanillaDumpTest*' --offline -q -i > /tmp/claude-1000/v1dump.log 2>&1
echo EXIT=$? >> /tmp/claude-1000/v1dump.log
