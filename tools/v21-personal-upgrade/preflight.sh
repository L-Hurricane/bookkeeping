#!/usr/bin/env bash
# V21 personal-upgrade preflight. Fails closed. Does not upgrade.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
source "$ROOT/lib.sh"

usage() {
  cat >&2 <<'EOF'
Usage: preflight.sh --env FILE --new-wt DIR --old-wt DIR --old-sha SHA --port N
Env: BK_V21_MOCK=1 BK_V21_MOCK_DIR=... for synthetic checks
     BK_V21_CONFIRM_STDIN=PREFLIGHT-OK to supply confirm non-interactively (tests)
EOF
  exit 2
}

ENV_FILE="" NEW_WT="" OLD_WT="" OLD_SHA="" PORT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENV_FILE="${2:-}"; shift 2 ;;
    --new-wt) NEW_WT="${2:-}"; shift 2 ;;
    --old-wt) OLD_WT="${2:-}"; shift 2 ;;
    --old-sha) OLD_SHA="${2:-}"; shift 2 ;;
    --port) PORT="${2:-}"; shift 2 ;;
    -h|--help) usage ;;
    *) bk_die "unknown arg: $1" ;;
  esac
done

[[ -n "$ENV_FILE" && -n "$NEW_WT" && -n "$OLD_WT" && -n "$OLD_SHA" && -n "$PORT" ]] || usage

bk_require_cmd git
bk_require_cmd shasum
bk_require_cmd python3

bk_unset_test_urls
bk_load_env_file "$ENV_FILE"
bk_assert_db_url
bk_assert_port "$PORT"
[[ "${SERVER_PORT:-}" == "$PORT" || -z "${SERVER_PORT:-}" ]] \
  || bk_die "SERVER_PORT in env ($SERVER_PORT) != --port ($PORT)"

bk_assert_new_artifact_tree "$NEW_WT"
bk_assert_old_artifact_tree "$OLD_WT" "$OLD_SHA"
bk_assert_pre_upgrade_schema

bk_log "preflight assertions passed (db=$BK_V21_TARGET_DB new=$BK_V21_EXPECTED_NEW_SHA old=$OLD_SHA port=$PORT)"
bk_require_confirm "PREFLIGHT-OK" "Type PREFLIGHT-OK to acknowledge preflight summary:"
bk_log "PREFLIGHT_OK"
