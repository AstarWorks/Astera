#!/usr/bin/env bash
# Harness-tooling test for session-budget.py (not a product unit test).
set -uo pipefail
HOOK="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/session-budget.py"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
export ASTERA_SESSION_BUDGET_DIR="$T/state"
PASS=0; FAIL=0
tr_file() { # $1 name $2 tokens
  python3 -c '
import json,sys
print(json.dumps({"type":"assistant","isSidechain":False,"message":{"usage":{"input_tokens":4,"cache_read_input_tokens":int(sys.argv[1]),"cache_creation_input_tokens":0}}}))' "$2" > "$T/$1.jsonl"
}
run() { # $1 mode $2 sid $3 transcript $4 prompt
  python3 -c '
import json,sys
print(json.dumps({"session_id":sys.argv[1],"transcript_path":sys.argv[2],"prompt":sys.argv[3]}))' "$2" "$3" "$4" | python3 "$HOOK" "$1" 2>/dev/null
}
expect() { # label pattern output   (pattern "" = empty output)
  if { [ -z "$2" ] && [ -z "$3" ]; } || { [ -n "$2" ] && [[ "$3" == *"$2"* ]]; }; then PASS=$((PASS+1)); echo "PASS: $1"; else FAIL=$((FAIL+1)); echo "FAIL: $1 ($3)"; fi
}
tr_file low 50000; tr_file warn 150000; tr_file high 250000
expect 'below warn passes silently' '' "$(run prompt s1 $T/low.jsonl 'do x')"
expect 'warn range warns' 'systemMessage' "$(run prompt s1 $T/warn.jsonl 'do x')"
expect 'warn does not block' '' "$(run prompt s1 $T/warn.jsonl 'do x' | grep -o '"decision"')"
expect 'block at 200k' '"decision": "block"' "$(run prompt s1 $T/high.jsonl 'do x' | sed 's/":"/": "/')"
expect 'handoff word exempt' '' "$(run prompt s1 $T/high.jsonl 'write the Handoff')"
expect '引き継ぎ exempt' '' "$(run prompt s1 $T/high.jsonl '引き継ぎを書いて')"
expect '/handoff exempt' '' "$(run prompt s1 $T/high.jsonl '/handoff')"
expect 'override off passes' '' "$(ASTERA_SESSION_BUDGET=off run prompt s1 $T/high.jsonl 'do x')"
expect 'missing transcript fails open' '' "$(run prompt s1 $T/nope.jsonl 'do x')"
expect 'garbage stdin fails open' '' "$(echo 'not json' | python3 "$HOOK" prompt 2>/dev/null)"
expect '1st compaction: no instruction' '' "$(run compact s2 $T/low.jsonl '')"
expect 'counter file written' '"compactions": 1' "$(cat $T/state/s2.json)"
expect '1 compaction does not block' '' "$(run prompt s2 $T/low.jsonl 'do x')"
expect '2nd compaction: write handoff' 'Write the track handoff' "$(run compact s2 $T/low.jsonl '')"
expect '2 compactions block' 'block' "$(run prompt s2 $T/low.jsonl 'do x')"
expect '2 compactions still allow handoff' '' "$(run prompt s2 $T/low.jsonl 'handoff')"
expect 'statusline format' 'ctx 150k / compactions 0' "$(run status s3 $T/warn.jsonl '')"
echo "pass=$PASS fail=$FAIL"; [ "$FAIL" -eq 0 ]
