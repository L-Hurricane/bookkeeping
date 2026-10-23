#!/bin/bash
# S2-T00R summarize or verify. The summary is created inside the evidence directory.
set -euo pipefail
umask 077
DIR="$(cd "$(dirname "$0")" && pwd)"
MODE="summarize"
if [[ "${1:-}" == "--verify" ]]; then
  MODE="verify"
  shift
fi
EVIDENCE="${1:-}"
if [[ -z "$EVIDENCE" || $# -gt 1 ]]; then
  echo "BLOCKED: usage evidence-dir | --verify evidence-dir" >&2
  exit 2
fi
exec /usr/bin/python3 "$DIR/ab_lite_safe.py" "$MODE" "$EVIDENCE"
