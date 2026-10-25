#!/usr/bin/env bash
# Harness-tooling test for gate-codex-lane.py (not a product unit test).
set -uo pipefail
GATE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/gate-codex-lane.py"
PASS=0; FAIL=0
# $1 label, $2 deny|allow, $3 command, $4 run_in_background (true|false)
check() {
  local out
  out="$(python3 -c '
import json,sys
print(json.dumps({"tool_name":"Bash","tool_input":{"command":sys.argv[1],"run_in_background":sys.argv[2]=="true"}}))
' "$3" "${4:-false}" | python3 "$GATE" 2>/dev/null)"
  if [ "$2" = deny ]; then
    case "$out" in *'"permissionDecision":"deny"'*) PASS=$((PASS+1)); echo "PASS: $1";; *) FAIL=$((FAIL+1)); echo "FAIL: $1 ($out)";; esac
  else
    case "$out" in *deny*) FAIL=$((FAIL+1)); echo "FAIL: $1 ($out)";; *) PASS=$((PASS+1)); echo "PASS: $1";; esac
  fi
}
L='scripts/codex-task.sh t1 /tmp/claude-1000/p.md'
check 'launcher foreground denied' deny "$L" false
check 'launcher run_in_background allowed' allow "$L" true
check 'launcher with env prefix, bg allowed' allow "CODEX_TIMEOUT=60 $L" true
check 'launcher trailing & denied even with bg' deny "$L &" true
check 'launcher nohup denied' deny "nohup $L" true
check 'launcher setsid nohup & denied' deny "setsid nohup $L > /dev/null 2>&1 &" true
check 'launcher disown denied' deny "$L & disown" true
check 'launcher via bash -c foreground denied' deny "bash -c '$L'" false
check 'raw implement-profile exec denied' deny 'codex exec -p astar-implement -s danger-full-access -C /wt - < p.md' true
check 'raw exec no sandbox denied' deny 'timeout 900 codex exec "do it"' true
check 'raw exec workspace-write denied' deny 'timeout 900 codex exec --sandbox workspace-write -- x' false
check 'raw exec detached nohup denied' deny 'nohup codex exec --sandbox read-only -- x &' true
check 'raw read-only exec trailing & denied' deny 'timeout 900 codex exec -s read-only -- x &' true
check 'raw read-only exec foreground allowed' allow 'timeout 900 codex exec --sandbox read-only -- investigate' false
check 'raw exec hidden after && denied' deny 'echo hi&&codex exec -s danger-full-access -- x' true
check 'codex review write-capable denied' deny 'codex review --uncommitted' false
check 'codex login allowed' allow 'codex login status' false
check 'codex --version allowed' allow 'codex --version' false
check 'mention in quoted string allowed' allow 'echo "run codex exec later"' false
check 'unrelated command allowed' allow 'git status' false
check 'malformed quoting fails open' allow "echo 'codex" false
if echo '{bad json' | python3 "$GATE" | grep -q '{}'; then PASS=$((PASS+1)); echo 'PASS: bad payload fails open'; else FAIL=$((FAIL+1)); echo 'FAIL: bad payload'; fi
echo "gate-codex-lane.test.sh: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
