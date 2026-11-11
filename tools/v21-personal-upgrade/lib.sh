#!/usr/bin/env bash
# Shared helpers for V21 personal-upgrade safety scripts.
# No credentials are printed. Not authorized to run against real bookkeeping
# unless a separate owner special approval says so.

set -euo pipefail

BK_V21_EXPECTED_NEW_SHA="${BK_V21_EXPECTED_NEW_SHA:-824a3ce3ccc3bbb1223ded64c5cdfb04eb2f3b03}"
BK_V21_EXPECTED_V21_FILE_SHA="${BK_V21_EXPECTED_V21_FILE_SHA:-525bf36b67633b1b4b5d9b77694195db4502dcab22ef02274c9c6eea8d45d5f9}"
BK_V21_TARGET_DB="${BK_V21_TARGET_DB:-bookkeeping}"
BK_V21_V21_RELPATH="backend/src/main/resources/db/migration/V21__transaction_counterparty_name.sql"

BK_V21_FORBIDDEN_DB_SUBSTRINGS=(
  bookkeeping_uat
  bookkeeping_test
  bookkeeping_migration_test
  bookkeeping_v21_restore_drill
  bk_personal_local
)

BK_V21_FORBIDDEN_PORTS=(18081 18082 18083)

bk_die() {
  echo "STOP: $*" >&2
  exit 1
}

bk_log() {
  echo "INFO: $*" >&2
}

bk_require_cmd() {
  command -v "$1" >/dev/null 2>&1 || bk_die "missing command: $1"
}

bk_sha256_file() {
  local f="$1"
  shasum -a 256 "$f" | awk '{print $1}'
}

# Load KEY=VAL env file into current shell. Never echoes values.
# Only whitelisted keys are exported.
bk_load_env_file() {
  local file="$1"
  [[ -f "$file" ]] || bk_die "env file not found: $file"
  local mode
  mode=$(stat -f '%OLp' "$file" 2>/dev/null || stat -c '%a' "$file")
  case "$mode" in
    600|400|700) ;;
    *) bk_die "env file mode must be 600/400/700 (got $mode): $file" ;;
  esac

  local line key val
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" || "$line" == \#* ]] && continue
    [[ "$line" == *"="* ]] || bk_die "invalid env line (no =)"
    key="${line%%=*}"
    val="${line#*=}"
    case "$key" in
      DB_URL|DB_USERNAME|DB_PASSWORD|SERVER_PORT|MYSQL_HOST|JAVA_HOME|BK_V21_BACKUP_PASSPHRASE) ;;
      *) bk_die "env key not allowed in upgrade env: $key" ;;
    esac
    printf -v "$key" '%s' "$val"
    export "$key"
  done <"$file"
}

bk_jdbc_database_name() {
  local url="${1:-}"
  [[ -n "$url" ]] || bk_die "DB_URL empty"
  python3 - "$url" <<'PY'
import sys, re
url = sys.argv[1]
m = re.search(r"jdbc:mysql://([^/?]+)/([^?]+)", url)
if not m:
    sys.stderr.write("STOP: DB_URL not a jdbc:mysql URL with database path\n")
    sys.exit(1)
host, db = m.group(1), m.group(2)
# host may be host:port
print(host.split(":")[0])
print(db)
PY
}

bk_assert_no_forbidden_substrings() {
  local label="$1"
  local value="$2"
  local s
  for s in "${BK_V21_FORBIDDEN_DB_SUBSTRINGS[@]}"; do
    if [[ "$value" == *"$s"* ]]; then
      bk_die "$label contains forbidden substring: $s"
    fi
  done
}

bk_assert_db_url() {
  local url="${DB_URL:-}"
  [[ -n "$url" ]] || bk_die "DB_URL unset"
  bk_assert_no_forbidden_substrings "DB_URL" "$url"
  bk_assert_no_forbidden_substrings "DB_USERNAME" "${DB_USERNAME:-}"

  local host db
  host="$(bk_jdbc_database_name "$url" | sed -n '1p')"
  db="$(bk_jdbc_database_name "$url" | sed -n '2p')"
  [[ "$host" == "127.0.0.1" || "$host" == "localhost" ]] \
    || bk_die "DB_URL host must be 127.0.0.1 or localhost (got $host)"
  [[ "$db" == "$BK_V21_TARGET_DB" ]] \
    || bk_die "DB_URL database must be exactly $BK_V21_TARGET_DB (got $db)"
}

bk_assert_port() {
  local port="${1:-}"
  [[ "$port" =~ ^[0-9]+$ ]] || bk_die "invalid port: $port"
  local p
  for p in "${BK_V21_FORBIDDEN_PORTS[@]}"; do
    [[ "$port" != "$p" ]] || bk_die "port $port is reserved for synthetic/UAT/drill"
  done
  (( port >= 1024 && port <= 65535 )) || bk_die "port out of range: $port"
}

bk_assert_new_artifact_tree() {
  local new_wt="$1"
  [[ -d "$new_wt/.git" || -f "$new_wt/.git" || -d "$new_wt" ]] || bk_die "NEW_WT missing: $new_wt"
  local head
  head="$(git -C "$new_wt" rev-parse HEAD)"
  [[ "$head" == "$BK_V21_EXPECTED_NEW_SHA" ]] \
    || bk_die "NEW_WT HEAD must be RC-02 $BK_V21_EXPECTED_NEW_SHA (got $head). Do not use docs-archive HEAD."
  local v21="$new_wt/$BK_V21_V21_RELPATH"
  [[ -f "$v21" ]] || bk_die "V21 file missing in NEW_WT"
  local got
  got="$(bk_sha256_file "$v21")"
  [[ "$got" == "$BK_V21_EXPECTED_V21_FILE_SHA" ]] \
    || bk_die "V21 file SHA mismatch (got $got)"
}

bk_assert_old_artifact_tree() {
  local old_wt="$1"
  local expected_old_sha="$2"
  [[ -n "$expected_old_sha" ]] || bk_die "OLD_SHA required (private owner record); refuse drill-default"
  [[ "$expected_old_sha" != "$BK_V21_EXPECTED_NEW_SHA" ]] \
    || bk_die "OLD_SHA must not equal RC-02"
  local head
  head="$(git -C "$old_wt" rev-parse HEAD)"
  [[ "$head" == "$expected_old_sha" ]] \
    || bk_die "OLD_WT HEAD ($head) != declared OLD_SHA ($expected_old_sha)"
  if [[ -f "$old_wt/$BK_V21_V21_RELPATH" ]]; then
    bk_die "OLD_WT must not contain V21 migration file"
  fi
}

bk_require_confirm() {
  local expected="$1"
  local prompt="$2"
  if [[ ! -t 0 ]] && [[ -z "${BK_V21_CONFIRM_STDIN:-}" ]]; then
    bk_die "refusing non-interactive confirm without BK_V21_CONFIRM_STDIN for tests"
  fi
  echo "$prompt" >&2
  local got
  if [[ -n "${BK_V21_CONFIRM_STDIN:-}" ]]; then
    got="$BK_V21_CONFIRM_STDIN"
  else
    IFS= read -r got || true
  fi
  [[ "$got" == "$expected" ]] || bk_die "confirm word mismatch (need exact: $expected)"
}

bk_mock_enabled() {
  [[ "${BK_V21_MOCK:-0}" == "1" ]]
}

bk_mysql_bin() {
  echo "${BK_V21_MYSQL_BIN:-$HOME/.local/share/bookkeeping-bootstrap/mysql-8.4.3-macos14-arm64/bin/mysql}"
}

bk_mysqldump_bin() {
  echo "${BK_V21_MYSQLDUMP_BIN:-$HOME/.local/share/bookkeeping-bootstrap/mysql-8.4.3-macos14-arm64/bin/mysqldump}"
}

bk_mysql_batch() {
  # Runs mysql with defaults-file OR uses mock fixture. Never logs password.
  local sql="$1"
  if bk_mock_enabled; then
    local mock_dir="${BK_V21_MOCK_DIR:-}"
    [[ -n "$mock_dir" && -d "$mock_dir" ]] || bk_die "BK_V21_MOCK_DIR required in mock mode"
    local f=""
    if [[ "$sql" == *"max_v"* ]]; then
      f="$mock_dir/flyway_max.txt"
    elif [[ "$sql" == *"counterparty_name"* ]]; then
      f="$mock_dir/counterparty_col.txt"
    elif [[ "$sql" == *"SCHEMA_NAME"* ]]; then
      f="$mock_dir/schema_exists.txt"
    else
      bk_die "no mock fixture for SQL"
    fi
    [[ -f "$f" ]] || bk_die "missing mock fixture: $f"
    cat "$f"
    return 0
  fi
  [[ "${BK_V21_ALLOW_LIVE_MYSQL:-0}" == "1" ]] \
    || bk_die "live MySQL disabled (set BK_V21_ALLOW_LIVE_MYSQL=1 only after owner special approval)"
  local cnf="${BK_V21_MYSQL_DEFAULTS_FILE:-}"
  [[ -n "$cnf" && -f "$cnf" ]] || bk_die "BK_V21_MYSQL_DEFAULTS_FILE required for live MySQL"
  local mode
  mode=$(stat -f '%OLp' "$cnf" 2>/dev/null || stat -c '%a' "$cnf")
  case "$mode" in
    600|400) ;;
    *) bk_die "mysql defaults-file mode must be 600/400" ;;
  esac
  "$(bk_mysql_bin)" --defaults-file="$cnf" --protocol=TCP -h127.0.0.1 --batch -N -e "$sql"
}

bk_read_flyway_max() {
  local out
  out="$(bk_mysql_batch "SELECT MAX(CAST(version AS UNSIGNED)) AS max_v FROM \`${BK_V21_TARGET_DB}\`.flyway_schema_history WHERE success=1;")"
  # take last non-empty token
  printf '%s\n' "$out" | awk 'NF{v=$NF} END{print v}'
}

bk_read_counterparty_col() {
  local out
  out="$(bk_mysql_batch "SELECT COUNT(*) AS counterparty_col FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='${BK_V21_TARGET_DB}' AND TABLE_NAME='transactions' AND COLUMN_NAME='counterparty_name';")"
  printf '%s\n' "$out" | awk 'NF{v=$NF} END{print v}'
}

bk_assert_pre_upgrade_schema() {
  local max_v col
  max_v="$(bk_read_flyway_max)"
  col="$(bk_read_counterparty_col)"
  [[ "$max_v" == "20" ]] || bk_die "Flyway max_v must be 20 before upgrade (got ${max_v:-empty})"
  [[ "$col" == "0" ]] || bk_die "counterparty_name must not exist before upgrade (count=$col)"
}

bk_unset_test_urls() {
  unset TEST_DB_URL TEST_DB_USERNAME TEST_DB_PASSWORD MIGRATION_TEST_DB_URL || true
}
