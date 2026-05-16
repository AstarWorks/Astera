#!/usr/bin/env bash
# PXC headless wrapper.
#
# Usage:
#   render.sh --check
#       Verify that PXC_BIN points to an executable Pixel Composer.
#
#   render.sh PROJECT [--out DIR] [--var KEY=VALUE ...] [--tags TAG ...]
#       Run PXC headlessly on PROJECT (.pxc file).
#       Export nodes inside the project drive the actual output.
#       Variables (--var k=v) are passed as PXC global variables.
#       Tags (--tags ...) limit which export nodes fire (per PXC CLI docs).
#
# Boundary: human-run only. Never called from Astera build or runtime.
# See ../README.md and ADR-0018.

set -euo pipefail

usage() {
    sed -n '2,17p' "$0"
    exit "${1:-2}"
}

# ---- check mode ----
if [[ "${1:-}" == "--check" ]]; then
    if [[ -z "${PXC_BIN:-}" ]]; then
        echo "PXC_BIN is not set. See tools/texture-gen/README.md." >&2
        exit 1
    fi
    if [[ ! -x "$PXC_BIN" ]]; then
        echo "PXC_BIN is not an executable: $PXC_BIN" >&2
        exit 1
    fi
    echo "PXC_BIN: $PXC_BIN"
    if command -v pxc-cli >/dev/null 2>&1; then
        echo "pxc-cli: $(command -v pxc-cli)"
    else
        echo "pxc-cli: NOT FOUND on PATH (optional, used for stdout capture)"
    fi
    exit 0
fi

[[ $# -ge 1 ]] || usage 2

PROJECT="$1"
shift
OUT_DIR=""
VARS=()
TAGS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --out)   OUT_DIR="$2"; shift 2 ;;
        --var)   VARS+=("$2"); shift 2 ;;
        --tags)  shift; while [[ $# -gt 0 && "$1" != --* ]]; do TAGS+=("$1"); shift; done ;;
        -h|--help) usage 0 ;;
        *)       echo "unknown option: $1" >&2; usage 2 ;;
    esac
done

if [[ ! -f "$PROJECT" ]]; then
    echo "error: project not found: $PROJECT" >&2
    exit 1
fi

if [[ -z "${PXC_BIN:-}" || ! -x "$PXC_BIN" ]]; then
    echo "error: PXC_BIN not set or not executable." >&2
    echo "       Run 'render.sh --check' for diagnostics." >&2
    exit 1
fi

# Resolve absolute paths so PXC working-dir surprises don't bite.
PROJECT_ABS=$(realpath "$PROJECT")
if [[ -n "$OUT_DIR" ]]; then
    mkdir -p "$OUT_DIR"
    OUT_ABS=$(realpath "$OUT_DIR")
    # PXC reads output paths from export nodes inside the project, but we
    # set ASTERA_OUT_DIR as a convenience global var that recipes can reference.
    VARS+=("ASTERA_OUT_DIR=$OUT_ABS")
fi

# Build PXC argv per docs.pixel-composer.com/misc/command_line.html:
#   pixelcomposer.exe <project> [tags...] [global vars...] -h
ARGS=("$PROJECT_ABS")
for tag in "${TAGS[@]}"; do ARGS+=("$tag"); done
for var in "${VARS[@]}"; do ARGS+=("$var"); done
ARGS+=("-h")

# Prefer pxc-cli wrapper if available (captures stdout); else direct.
if command -v pxc-cli >/dev/null 2>&1; then
    exec pxc-cli "$PXC_BIN" "${ARGS[@]}"
else
    exec "$PXC_BIN" "${ARGS[@]}"
fi
