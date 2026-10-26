#!/bin/bash
# retry.sh <view> <prefix> <tries> [HIDE_HUD]: capture until no true duplicate in frames 1..58 and not invalid; copy good take to director26/<view>
D=/tmp/claude-1000/-IdeaProjects-Astera/5316b944-e8ca-400e-bc5c-57bbd6f2ecd3/scratchpad/director26
O=/IdeaProjects/Astera/.claude/worktrees/agent-a5cad8554791e291b/build/vfx-shot/bastion-greatsword-s1/$1
for i in $(seq 1 $3); do
  bash $D/shot.sh $1 72 $2$i $4
  python3 $D/qcheck.py $O > $D/$2$i.q 2>&1
  if python3 - "$D/$2$i.q" <<'EOF'
import sys, re, ast
t = open(sys.argv[1]).read().splitlines()
meta = ast.literal_eval(t[0]); cq = meta.get('captureQuality') or {}
if cq.get('invalid') or meta.get('codeDirty') or meta.get('contentDirty'):
    sys.exit(1)
d = ast.literal_eval(t[1].split(':', 1)[1].strip())
bad = [x for x in d if 1 <= x <= 58 and not (9 <= x <= 13 or 32 <= x <= 36)]
sys.exit(1 if bad else 0)
EOF
  then
    rm -rf $D/$1; cp -r $O $D/$1; echo "GOOD $2$i"; exit 0
  fi
  echo "BAD $2$i"; head -2 $D/$2$i.q
done
exit 1
