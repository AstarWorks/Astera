#!/usr/bin/env bash
# C4: no founder report without something visible. Usage: tools/report-check.sh <sha>   (make report-check SHA=<sha>)
# Reads the dev autodeploy log; prints the first line of a founder report. Exit 1 if <sha> is not deployed.
set -u
sha="${1:-}"
log="${DEPLOY_LOG:-/IdeaProjects/Astera/.claude/state/dev-deploy/deploy.log}"
if [ -z "$sha" ]; then echo "usage: make report-check SHA=<sha>" >&2; exit 2; fi
line=""
[ -f "$log" ] && line=$(grep -E " deployed ${sha}[0-9a-f]*( |$)" "$log" | tail -1)
if [ -z "$line" ]; then
  echo "まだ見られるものはありません (${sha:0:9} not deployed)"
  exit 1
fi
time=${line%% *}
full=$(echo "$line" | awk '{print $3}')
echo "見られる場所: dev (${full:0:9}, ${time})"
echo "skill-compare: https://skill-compare.astarworks.com/ (case page: /?case=<case-id>, sheet: /api/cases/<case-id>/sheet.png)"
