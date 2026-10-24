#!/bin/bash
# S2-T00R launch. Checks the approved plan file, then records one harmless command.
set -euo pipefail
umask 077
DIR="$(cd "$(dirname "$0")" && pwd)"
TASK="" ROLE="" WORKSPACE="" PLAN="" BASE="" EVIDENCE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --task) TASK="${2:-}"; shift 2 ;;
    --role) ROLE="${2:-}"; shift 2 ;;
    --workspace) WORKSPACE="${2:-}"; shift 2 ;;
    --plan-sha) PLAN="${2:-}"; shift 2 ;;
    --baseline) BASE="${2:-}"; shift 2 ;;
    --evidence-dir) EVIDENCE="${2:-}"; shift 2 ;;
    *) echo "BLOCKED: unknown argument" >&2; exit 2 ;;
  esac
done
exec /usr/bin/python3 "$DIR/ab_lite_safe.py" launch "$TASK" "$ROLE" "$WORKSPACE" "$PLAN" "$BASE" "$EVIDENCE"
