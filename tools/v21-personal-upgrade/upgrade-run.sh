#!/usr/bin/env bash
# Start RC-02 against asserted bk_personal_local DSN only after explicit confirm.
# Default does not run Spring unless BK_V21_EXECUTE_UPGRADE=1 (owner).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
source "$ROOT/lib.sh"

usage() {
  cat >&2 <<'EOF'
Usage: upgrade-run.sh --env FILE --new-wt DIR --old-wt DIR --old-sha SHA --port N \
         --backup-file FILE --backup-sha SHA256 [--manifest FILE]
Refuses default execution. Owner sets BK_V21_EXECUTE_UPGRADE=1 after special approval.
Requires backup file + manifest binding (SHA + instance identity).
Live launch always passes --spring.datasource.url=$DB_URL (never rely on
application-local.yml default jdbc:.../bookkeeping).
EOF
  exit 2
}

ENV_FILE="" NEW_WT="" OLD_WT="" OLD_SHA="" PORT="" BACKUP_SHA="" BACKUP_FILE="" MANIFEST=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENV_FILE="${2:-}"; shift 2 ;;
    --new-wt) NEW_WT="${2:-}"; shift 2 ;;
    --old-wt) OLD_WT="${2:-}"; shift 2 ;;
    --old-sha) OLD_SHA="${2:-}"; shift 2 ;;
    --port) PORT="${2:-}"; shift 2 ;;
    --backup-file) BACKUP_FILE="${2:-}"; shift 2 ;;
    --backup-sha) BACKUP_SHA="${2:-}"; shift 2 ;;
    --manifest) MANIFEST="${2:-}"; shift 2 ;;
    -h|--help) usage ;;
    *) bk_die "unknown arg: $1" ;;
  esac
done
[[ -n "$ENV_FILE" && -n "$NEW_WT" && -n "$OLD_WT" && -n "$OLD_SHA" && -n "$PORT" && -n "$BACKUP_SHA" && -n "$BACKUP_FILE" ]] || usage
[[ "$BACKUP_SHA" =~ ^[a-f0-9]{64}$ ]] || bk_die "backup sha must be 64 hex chars"

bk_unset_test_urls
bk_load_env_file "$ENV_FILE"
bk_assert_db_url
bk_assert_port "$PORT"
bk_assert_new_artifact_tree "$NEW_WT"
bk_assert_old_artifact_tree "$OLD_WT" "$OLD_SHA"
bk_assert_instance_identity "$NEW_WT"
bk_assert_backup_binding "$BACKUP_FILE" "$BACKUP_SHA" "${MANIFEST:-}"
bk_assert_pre_upgrade_schema

bk_log "upgrade gate: new=$BK_V21_EXPECTED_NEW_SHA port=$PORT backup_sha=$BACKUP_SHA"
bk_require_confirm "UPGRADE-BOOKKEEPING" "Type UPGRADE-BOOKKEEPING to allow upgrade start:"

if [[ "${BK_V21_EXECUTE_UPGRADE:-0}" != "1" ]]; then
  bk_log "confirm accepted but BK_V21_EXECUTE_UPGRADE!=1 — dry stop (safe default)"
  bk_log "UPGRADE_DRY_OK"
  exit 0
fi

[[ "${BK_V21_ALLOW_LIVE_MYSQL:-0}" == "1" ]] \
  || bk_die "refusing live upgrade process without BK_V21_ALLOW_LIVE_MYSQL=1"

# Launch is owner-operated; we still refuse forbidden ports/DSN above.
# application-local.yml may default to jdbc:.../bookkeeping — refuse that fallback.
[[ -n "${DB_URL:-}" ]] || bk_die "DB_URL unset before Spring launch"
[[ "${BK_V21_JDBC_DB:-}" == "$BK_V21_TARGET_DB" ]] \
  || bk_die "JDBC db must be $BK_V21_TARGET_DB before Spring launch"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/bookkeeping-bootstrap/zulu25.36.205-ca-jdk25.0.4.1-macosx_aarch64/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"
export DB_URL
bk_log "starting Spring Boot from NEW_WT (owner live mode; explicit spring.datasource.url)"
cd "$NEW_WT/backend"
exec ./mvnw -DskipTests spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dspring.profiles.active=local" \
  -Dspring-boot.run.arguments="--server.port=${PORT} --spring.datasource.url=${DB_URL}"
