#!/usr/bin/env bash
# Shared helpers for V21 personal-upgrade safety scripts.
# No credentials are printed. Not authorized to operate the personal formal ledger
# unless a separate owner special approval says so.

set -euo pipefail

BK_V21_EXPECTED_NEW_SHA="${BK_V21_EXPECTED_NEW_SHA:-824a3ce3ccc3bbb1223ded64c5cdfb04eb2f3b03}"
BK_V21_EXPECTED_V21_FILE_SHA="${BK_V21_EXPECTED_V21_FILE_SHA:-525bf36b67633b1b4b5d9b77694195db4502dcab22ef02274c9c6eea8d45d5f9}"
# Pinned personal formal target (owner-approved 2026-11-12). Not env-overridable.
# Legacy name "bookkeeping" is refused (forbidden substring + exact-match gate).
BK_V21_TARGET_DB="bk_personal_local"
BK_V21_V21_RELPATH="backend/src/main/resources/db/migration/V21__transaction_counterparty_name.sql"

# Substring denylist for DB_URL / username. Plain "bookkeeping" also covers
# bookkeeping_uat / _test / _migration_test / _v21_restore_drill path segments.
BK_V21_FORBIDDEN_DB_SUBSTRINGS=(
  bookkeeping
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

# Parse jdbc:mysql URL → host, port (default 3306), database (exact path segment).
# Prints three lines. Does not treat substring matches as proof of safety.
bk_jdbc_parse() {
  local url="${1:-}"
  [[ -n "$url" ]] || bk_die "DB_URL empty"
  python3 - "$url" <<'PY'
import sys, re
url = sys.argv[1]
m = re.search(r"^jdbc:mysql://([^/?]+)/([^?]+)", url)
if not m:
    sys.stderr.write("STOP: DB_URL not a jdbc:mysql URL with database path\n")
    sys.exit(1)
hostport, db = m.group(1), m.group(2)
if "/" in db or not db or db.strip() != db:
    sys.stderr.write("STOP: DB_URL database path invalid\n")
    sys.exit(1)
if ":" in hostport:
    host, port_s = hostport.rsplit(":", 1)
    if not port_s.isdigit():
        sys.stderr.write("STOP: DB_URL port invalid\n")
        sys.exit(1)
    port = int(port_s)
else:
    host, port = hostport, 3306
print(host)
print(port)
print(db)
PY
}

# Back-compat helper used by older call sites: host\ndb
bk_jdbc_database_name() {
  local parsed host db
  parsed="$(bk_jdbc_parse "${1:-}")"
  host="$(printf '%s\n' "$parsed" | sed -n '1p')"
  db="$(printf '%s\n' "$parsed" | sed -n '3p')"
  printf '%s\n%s\n' "$host" "$db"
}

bk_normalize_host() {
  case "$1" in
    localhost|LOCALHOST) printf '%s\n' "127.0.0.1" ;;
    *) printf '%s\n' "$1" ;;
  esac
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

  local parsed host port db
  parsed="$(bk_jdbc_parse "$url")"
  host="$(printf '%s\n' "$parsed" | sed -n '1p')"
  port="$(printf '%s\n' "$parsed" | sed -n '2p')"
  db="$(printf '%s\n' "$parsed" | sed -n '3p')"
  host="$(bk_normalize_host "$host")"
  [[ "$host" == "127.0.0.1" ]] \
    || bk_die "DB_URL host must be 127.0.0.1 or localhost (got $host)"
  [[ "$db" == "$BK_V21_TARGET_DB" ]] \
    || bk_die "DB_URL database must be exactly $BK_V21_TARGET_DB (got $db)"
  [[ "$port" =~ ^[0-9]+$ ]] || bk_die "DB_URL port invalid: $port"
  BK_V21_JDBC_HOST="$host"
  BK_V21_JDBC_PORT="$port"
  BK_V21_JDBC_DB="$db"
  export BK_V21_JDBC_HOST BK_V21_JDBC_PORT BK_V21_JDBC_DB
}

# Probe MySQL client identity. Prints: host_or_dash port server_uuid schema_db
# schema_db must equal DATABASE() after connecting to target schema.
bk_mysql_probe_identity() {
  if bk_mock_enabled; then
    local mock_dir="${BK_V21_MOCK_DIR:-}"
    local f="${mock_dir}/mysql_identity.txt"
    [[ -n "$mock_dir" && -f "$f" ]] || bk_die "missing mock mysql identity fixture: $f"
    # expected lines: host port server_uuid database
    local lines
    lines="$(wc -l <"$f" | tr -d ' ')"
    [[ "$lines" -ge 4 ]] || bk_die "mysql_identity.txt must have host/port/uuid/database lines"
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
  # Connect WITH the target schema so DATABASE() is authoritative evidence.
  "$(bk_mysql_bin)" --defaults-file="$cnf" --protocol=TCP -h127.0.0.1 \
    -D "$BK_V21_TARGET_DB" --batch -N -e \
    "SELECT @@hostname, @@port, @@server_uuid, DATABASE();"
}

# Cross-check Spring-bound DB_URL (env → application.yml ${DB_URL}) vs live/mock MySQL identity.
# Optional arg: NEW_WT path to verify Spring Boot yaml binding.
bk_assert_instance_identity() {
  local new_wt="${1:-}"
  [[ -n "${BK_V21_JDBC_HOST:-}" && -n "${BK_V21_JDBC_PORT:-}" && -n "${BK_V21_JDBC_DB:-}" ]] \
    || bk_die "JDBC identity unset; call bk_assert_db_url first"

  if [[ -n "$new_wt" ]]; then
    local yml="$new_wt/backend/src/main/resources/application.yml"
    [[ -f "$yml" ]] || bk_die "Spring application.yml missing: $yml"
    grep -Eq '^[[:space:]]*url:[[:space:]]*\$\{DB_URL\}' "$yml" \
      || bk_die "Spring datasource url must be exactly \${DB_URL} (refuse non-env binding)"
    # application-local.yml may still ship a jdbc:.../bookkeeping default. That is a
    # residual risk: live upgrade must pass --spring.datasource.url=$DB_URL explicitly
    # (see upgrade-run.sh). Do not treat the local-profile default as the target.
    local local_yml="$new_wt/backend/src/main/resources/application-local.yml"
    if [[ -f "$local_yml" ]] && grep -Eq 'bookkeeping' "$local_yml"; then
      bk_log "WARN application-local.yml still mentions bookkeeping default; runtime must use explicit DB_URL (not profile fallback)"
    fi
  fi

  local probe host_p port_p uuid_p db_p
  probe="$(bk_mysql_probe_identity)"
  host_p="$(printf '%s\n' "$probe" | sed -n '1p' | tr -d '\r')"
  port_p="$(printf '%s\n' "$probe" | sed -n '2p' | tr -d '\r')"
  uuid_p="$(printf '%s\n' "$probe" | sed -n '3p' | tr -d '\r')"
  db_p="$(printf '%s\n' "$probe" | sed -n '4p' | tr -d '\r')"

  [[ -n "$port_p" && -n "$uuid_p" && -n "$db_p" ]] \
    || bk_die "MySQL identity evidence incomplete (need port, server_uuid, DATABASE())"

  # Host from @@hostname may be machine name; for local-only policy we require JDBC host loopback
  # and MySQL TCP port + DATABASE() must match JDBC. Optional MYSQL_HOST env must agree if set.
  if [[ -n "${MYSQL_HOST:-}" ]]; then
    local mh
    mh="$(bk_normalize_host "$MYSQL_HOST")"
    [[ "$mh" == "$BK_V21_JDBC_HOST" ]] \
      || bk_die "MYSQL_HOST ($mh) != JDBC host ($BK_V21_JDBC_HOST)"
  fi

  [[ "$db_p" == "$BK_V21_TARGET_DB" ]] \
    || bk_die "MySQL DATABASE() must be exactly $BK_V21_TARGET_DB (got ${db_p:-empty}) — not string-contains"
  [[ "$db_p" == "$BK_V21_JDBC_DB" ]] \
    || bk_die "MySQL DATABASE() ($db_p) != JDBC database ($BK_V21_JDBC_DB)"
  [[ "$port_p" == "$BK_V21_JDBC_PORT" ]] \
    || bk_die "MySQL @@port ($port_p) != JDBC port ($BK_V21_JDBC_PORT) — client/Spring instance mismatch"

  if [[ -n "${BK_V21_EXPECT_SERVER_UUID:-}" ]]; then
    [[ "$uuid_p" == "$BK_V21_EXPECT_SERVER_UUID" ]] \
      || bk_die "MySQL @@server_uuid mismatch (expected pinned uuid)"
  fi

  BK_V21_MYSQL_PORT="$port_p"
  BK_V21_MYSQL_SERVER_UUID="$uuid_p"
  BK_V21_MYSQL_DATABASE="$db_p"
  BK_V21_MYSQL_HOSTNAME="$host_p"
  export BK_V21_MYSQL_PORT BK_V21_MYSQL_SERVER_UUID BK_V21_MYSQL_DATABASE BK_V21_MYSQL_HOSTNAME
  bk_log "instance identity ok jdbc=${BK_V21_JDBC_HOST}:${BK_V21_JDBC_PORT}/${BK_V21_JDBC_DB} mysql_port=$port_p uuid=${uuid_p:0:8}…"
}

bk_manifest_path_for_backup() {
  local backup="$1"
  printf '%s.manifest\n' "$backup"
}

bk_write_backup_manifest() {
  local backup="$1"
  local sha="$2"
  local enc="${3:-}"
  [[ -f "$backup" ]] || bk_die "backup missing for manifest"
  [[ "$sha" =~ ^[a-f0-9]{64}$ ]] || bk_die "manifest sha invalid"
  [[ -n "${BK_V21_JDBC_HOST:-}" && -n "${BK_V21_JDBC_PORT:-}" && -n "${BK_V21_MYSQL_SERVER_UUID:-}" ]] \
    || bk_die "instance identity required before writing manifest"

  local manifest
  manifest="$(bk_manifest_path_for_backup "$backup")"
  umask 077
  cat >"$manifest" <<EOF
manifest_version=1
target_db=${BK_V21_TARGET_DB}
jdbc_host=${BK_V21_JDBC_HOST}
jdbc_port=${BK_V21_JDBC_PORT}
mysql_port=${BK_V21_MYSQL_PORT}
mysql_server_uuid=${BK_V21_MYSQL_SERVER_UUID}
mysql_database=${BK_V21_MYSQL_DATABASE}
backup_basename=$(basename "$backup")
backup_sha256=${sha}
backup_enc_basename=$(basename "${enc:-}")
created_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
new_business_sha=${BK_V21_EXPECTED_NEW_SHA}
EOF
  chmod 600 "$manifest"
  [[ -s "$manifest" ]] || bk_die "manifest empty"
  bk_log "manifest written $(basename "$manifest")"
}

# Load KEY=VAL manifest into prefixed vars BK_V21_MF_*
bk_load_backup_manifest() {
  local manifest="$1"
  [[ -f "$manifest" ]] || bk_die "backup manifest missing: $manifest"
  local mode
  mode=$(stat -f '%OLp' "$manifest" 2>/dev/null || stat -c '%a' "$manifest")
  case "$mode" in
    600|400) ;;
    *) bk_die "manifest mode must be 600/400 (got $mode)" ;;
  esac
  local line key val
  BK_V21_MF_manifest_version=""
  BK_V21_MF_target_db=""
  BK_V21_MF_jdbc_host=""
  BK_V21_MF_jdbc_port=""
  BK_V21_MF_mysql_port=""
  BK_V21_MF_mysql_server_uuid=""
  BK_V21_MF_mysql_database=""
  BK_V21_MF_backup_basename=""
  BK_V21_MF_backup_sha256=""
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" || "$line" == \#* ]] && continue
    [[ "$line" == *"="* ]] || bk_die "invalid manifest line"
    key="${line%%=*}"
    val="${line#*=}"
    case "$key" in
      manifest_version|target_db|jdbc_host|jdbc_port|mysql_port|mysql_server_uuid|mysql_database|backup_basename|backup_sha256|backup_enc_basename|created_utc|new_business_sha) ;;
      *) bk_die "manifest key not allowed: $key" ;;
    esac
    printf -v "BK_V21_MF_${key}" '%s' "$val"
  done <"$manifest"
  [[ "${BK_V21_MF_manifest_version}" == "1" ]] || bk_die "unsupported manifest_version"
  [[ -n "${BK_V21_MF_backup_sha256}" && -n "${BK_V21_MF_target_db}" && -n "${BK_V21_MF_mysql_server_uuid}" ]] \
    || bk_die "manifest missing required identity/sha fields"
}

# Bind backup file + manifest + current instance identity. Fail-closed.
bk_assert_backup_binding() {
  local backup="$1"
  local expect_sha="${2:-}"
  local manifest="${3:-}"
  [[ -n "$backup" ]] || bk_die "backup path required"
  [[ -f "$backup" ]] || bk_die "backup missing: $backup"
  [[ -s "$backup" ]] || bk_die "backup empty: $backup"
  if [[ -z "$manifest" ]]; then
    manifest="$(bk_manifest_path_for_backup "$backup")"
  fi
  bk_load_backup_manifest "$manifest"

  local got
  got="$(bk_sha256_file "$backup")"
  [[ "$got" == "${BK_V21_MF_backup_sha256}" ]] \
    || bk_die "backup SHA != manifest (file may be modified)"
  if [[ -n "$expect_sha" ]]; then
    [[ "$expect_sha" =~ ^[a-f0-9]{64}$ ]] || bk_die "expected backup sha must be 64 hex"
    [[ "$got" == "$expect_sha" ]] || bk_die "backup SHA != --backup-sha argument"
    [[ "$expect_sha" == "${BK_V21_MF_backup_sha256}" ]] || bk_die "--backup-sha != manifest sha"
  fi
  [[ "$(basename "$backup")" == "${BK_V21_MF_backup_basename}" ]] \
    || bk_die "backup basename != manifest backup_basename"
  [[ "${BK_V21_MF_target_db}" == "$BK_V21_TARGET_DB" ]] \
    || bk_die "manifest target_db != $BK_V21_TARGET_DB"
  [[ "${BK_V21_MF_mysql_database}" == "$BK_V21_TARGET_DB" ]] \
    || bk_die "manifest mysql_database != $BK_V21_TARGET_DB"

  [[ -n "${BK_V21_JDBC_HOST:-}" && -n "${BK_V21_MYSQL_SERVER_UUID:-}" ]] \
    || bk_die "current instance identity required before backup binding"
  [[ "${BK_V21_MF_jdbc_host}" == "${BK_V21_JDBC_HOST}" ]] \
    || bk_die "manifest jdbc_host != current JDBC host"
  [[ "${BK_V21_MF_jdbc_port}" == "${BK_V21_JDBC_PORT}" ]] \
    || bk_die "manifest jdbc_port != current JDBC port"
  [[ "${BK_V21_MF_mysql_port}" == "${BK_V21_MYSQL_PORT}" ]] \
    || bk_die "manifest mysql_port != current MySQL @@port"
  [[ "${BK_V21_MF_mysql_server_uuid}" == "${BK_V21_MYSQL_SERVER_UUID}" ]] \
    || bk_die "manifest mysql_server_uuid != current instance uuid"
  [[ "${BK_V21_MF_mysql_database}" == "${BK_V21_MYSQL_DATABASE}" ]] \
    || bk_die "manifest mysql_database != current DATABASE()"
  bk_log "backup binding ok sha=$got"
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
