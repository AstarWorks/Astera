#!/usr/bin/env bash
# ADR-0247: ask the bot runner of the LIVE stack (compose project astera-dev) for AI bots that queue a lane.
#
#   scripts/bots.sh [N] [LANE]        # or: make bots N=2 LANE=carry-tnt
#
# Publishes {"requestId","lane","count","requestedBy"} on Redis `astera:bots:request` and prints the runner's
# replies from `astera:bots:status` until the bots are queued (or the request failed). The bots then wait in the
# queue like players: join the lane yourself (or request enough bots to fill it) and the match starts.
set -uo pipefail
cd "$(dirname "$0")/.."

COUNT="${1:-${N:-2}}"
LANE="${2:-${LANE:-norma}}"
case "$COUNT" in ''|*[!0-9]*) echo "[bots] N must be a number (got '$COUNT')"; exit 2;; esac
case "$LANE" in *[!A-Za-z0-9_-]*|'') echo "[bots] LANE must be a lane id like norma (got '$LANE')"; exit 2;; esac

COMPOSE=(docker compose -f docker/docker-compose.yml)
if ! "${COMPOSE[@]}" ps --status running --services 2>/dev/null | grep -qx redis; then
  echo "[bots] the stack is not running; start it with 'make docker-up'"; exit 2
fi
if ! "${COMPOSE[@]}" ps --status running --services 2>/dev/null | grep -qx bot-runner; then
  echo "[bots] starting the bot-runner service (first start builds the image)"
  "${COMPOSE[@]}" up -d --build bot-runner || { echo "[bots] could not start bot-runner"; exit 2; }
  sleep 4
fi

ID="make-bots-$(date +%s)-$$"
OUT="$(mktemp)"
trap 'kill "$SUB" 2>/dev/null; rm -f "$OUT"' EXIT
# Subscribe first so the replies cannot be missed; redis-cli prints a subscribe confirmation, then the messages.
"${COMPOSE[@]}" exec -T redis redis-cli SUBSCRIBE astera:bots:status > "$OUT" 2>&1 &
SUB=$!
sleep 1
REQ="{\"requestId\":\"$ID\",\"lane\":\"$LANE\",\"count\":$COUNT,\"requestedBy\":\"make-bots\"}"
"${COMPOSE[@]}" exec -T redis redis-cli PUBLISH astera:bots:request "$REQ" > /dev/null || { echo "[bots] publish failed"; exit 2; }
echo "[bots] requested $COUNT bot(s) for lane '$LANE' (request $ID)"

SHOWN=0
for _ in $(seq 1 240); do
  sleep 1
  while IFS= read -r line; do
    case "$line" in *"\"requestId\":\"$ID\""*)
      echo "[bots] $line"
      SHOWN=$((SHOWN + 1))
      case "$line" in *'"state":"queued"'*) echo "[bots] queued: join '$LANE' to start the match"; exit 0;; *'"state":"failed"'*) exit 1;; esac
    ;; esac
  done < <(grep "\"requestId\":\"$ID\"" "$OUT" | tail -n +$((SHOWN + 1)))
done
echo "[bots] no final status within 4 minutes (bots log in one by one, ~5 s each); see: docker compose -f docker/docker-compose.yml logs bot-runner"
exit 1
