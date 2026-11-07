#!/usr/bin/env bash
# statusLine command: "ctx <N>k / compactions <M>" (ADR-0323). Never fails.
exec python3 "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/session-budget.py" status 2>/dev/null || true
