#!/usr/bin/env bash
# Protected full restore to pre-V21 backup. Default: dry-run only.
# Blanking/import of a live V21 schema requires BK_V21_ALLOW_BLANK_RESTORE=1
# (separate special approval). Direct overwrite of existing V21 is refused.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
source "$ROOT/lib.sh"

usage() {
  cat >&2 <<'EOF'
Usage: restore.sh --env FILE --backup FILE --backup-sha SHA256 --old-wt DIR --old-sha SHA [--manifest FILE] [--execute]
Default is dry-run. --execute still requires BK_V21_ALLOW_BLANK_RESTORE=1 and confirm.
EOF
  exit 2
}

ENV_FILE="" BACKUP="" BACKUP_SHA="" OLD_WT="" OLD_SHA="" MANIFEST="" EXECUTE=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENV_FILE="${2:-}"; shift 2 ;;
    --backup) BACKUP="${2:-}"; shift 2 ;;
    --backup-sha) BACKUP_SHA="${2:-}"; shift 2 ;;
    --old-wt) OLD_WT="${2:-}"; shift 2 ;;
    --old-sha) OLD_SHA="${2:-}"; shift 2 ;;
    --manifest) MANIFEST="${2:-}"; shift 2 ;;
    --execute) EXECUTE=1; shift ;;
    -h|--help) usage ;;
    *) bk_die "unknown arg: $1" ;;
  esac
done
[[ -n "$ENV_FILE" && -n "$BACKUP" && -n "$BACKUP_SHA" && -n "$OLD_WT" && -n "$OLD_SHA" ]] || usage

bk_require_cmd shasum
bk_unset_test_urls
bk_load_env_file "$ENV_FILE"
bk_assert_db_url
bk_assert_old_artifact_tree "$OLD_WT" "$OLD_SHA"
bk_assert_instance_identity
bk_assert_backup_binding "$BACKUP" "$BACKUP_SHA" "${MANIFEST:-}"

grep -q "Database: ${BK_V21_TARGET_DB}" "$BACKUP" || bk_die "backup not for target db"
grep -q "flyway_schema_history" "$BACKUP" || bk_die "backup missing flyway history"

# Detect current schema version via mock/live
MAX_NOW="$(bk_read_flyway_max || true)"
COL_NOW="$(bk_read_counterparty_col || true)"
bk_log "current flyway_max=${MAX_NOW:-unknown} counterparty_col=${COL_NOW:-unknown}"

if [[ "${MAX_NOW:-}" == "21" || "${COL_NOW:-}" != "0" ]]; then
  if [[ "${BK_V21_ALLOW_BLANK_RESTORE:-0}" != "1" ]]; then
    bk_die "target appears V21 (or has counterparty_name); refuse restore without BK_V21_ALLOW_BLANK_RESTORE=1 (blank-then-import). Direct overwrite forbidden."
  fi
fi

if [[ "$EXECUTE" -ne 1 ]]; then
  bk_log "DRY-RUN restore plan: verify backup SHA=$BACKUP_SHA; blank ${BK_V21_TARGET_DB} only if approved; import full dump; start OLD_WT=$OLD_SHA"
  bk_log "RESTORE_DRY_OK"
  exit 0
fi

bk_require_confirm "RESTORE-BOOKKEEPING-FULL" "Type RESTORE-BOOKKEEPING-FULL to execute blank+import (destructive):"

[[ "${BK_V21_ALLOW_BLANK_RESTORE:-0}" == "1" ]] \
  || bk_die "--execute refused: BK_V21_ALLOW_BLANK_RESTORE!=1"
[[ "${BK_V21_ALLOW_LIVE_MYSQL:-0}" == "1" ]] \
  || bk_die "--execute refused: BK_V21_ALLOW_LIVE_MYSQL!=1"
[[ "${BK_V21_EXECUTE_RESTORE:-0}" == "1" ]] \
  || bk_die "--execute refused: BK_V21_EXECUTE_RESTORE!=1 (final owner arming switch)"

cnf="${BK_V21_MYSQL_DEFAULTS_FILE:-}"
[[ -n "$cnf" && -f "$cnf" ]] || bk_die "BK_V21_MYSQL_DEFAULTS_FILE required"

# Destructive path — only after all gates. Still scoped to TARGET_DB name only.
bk_log "blanking and importing ${BK_V21_TARGET_DB} (owner-armed)"
"$(bk_mysql_bin)" --defaults-file="$cnf" --protocol=TCP -h127.0.0.1 --batch -e \
  "DROP DATABASE \`${BK_V21_TARGET_DB}\`; CREATE DATABASE \`${BK_V21_TARGET_DB}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
"$(bk_mysql_bin)" --defaults-file="$cnf" --protocol=TCP -h127.0.0.1 --batch <"$BACKUP"
bk_log "RESTORE_EXECUTED — start old app from $OLD_WT ($OLD_SHA) manually and verify fingerprints"
