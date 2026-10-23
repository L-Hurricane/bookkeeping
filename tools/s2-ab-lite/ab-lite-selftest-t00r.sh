#!/bin/bash
# S2-T00R safety self-test. Does not delete selftest-run or other historical evidence.
set -euo pipefail
umask 077
DIR="$(cd "$(dirname "$0")" && pwd)"
exec /usr/bin/python3 "$DIR/ab_lite_selftest_t00r.py"
