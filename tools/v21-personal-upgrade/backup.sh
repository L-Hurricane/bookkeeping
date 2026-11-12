#!/usr/bin/env bash
# Logical backup + SHA + independent encrypted copy. Fail-closed.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
source "$ROOT/lib.sh"

usage() {
  cat >&2 <<'EOF'
Usage: backup.sh --env FILE --backup-dir DIR --encrypt-dir DIR
Requires BK_V21_BACKUP_PASSPHRASE in env file for encryption (never logged).
Mock: BK_V21_MOCK=1 writes a synthetic dump instead of mysqldump.
EOF
  exit 2
}

ENV_FILE="" BACKUP_DIR="" ENCRYPT_DIR=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENV_FILE="${2:-}"; shift 2 ;;
    --backup-dir) BACKUP_DIR="${2:-}"; shift 2 ;;
    --encrypt-dir) ENCRYPT_DIR="${2:-}"; shift 2 ;;
    -h|--help) usage ;;
    *) bk_die "unknown arg: $1" ;;
  esac
done
[[ -n "$ENV_FILE" && -n "$BACKUP_DIR" && -n "$ENCRYPT_DIR" ]] || usage
[[ "$BACKUP_DIR" != "$ENCRYPT_DIR" ]] || bk_die "encrypt-dir must differ from backup-dir"

bk_require_cmd shasum
bk_require_cmd openssl
bk_require_cmd gzip

bk_unset_test_urls
bk_load_env_file "$ENV_FILE"
bk_assert_db_url
bk_assert_instance_identity
[[ -n "${BK_V21_BACKUP_PASSPHRASE:-}" ]] || bk_die "BK_V21_BACKUP_PASSPHRASE missing in env file"

mkdir -p "$BACKUP_DIR" "$ENCRYPT_DIR"
STAMP="$(date +%Y%m%dT%H%M%S)"
BACKUP="$BACKUP_DIR/${BK_V21_TARGET_DB}-pre-v21-${STAMP}.sql"
ENC="$ENCRYPT_DIR/${BK_V21_TARGET_DB}-pre-v21-${STAMP}.sql.gz.enc"

cleanup_partial() {
  rm -f "$BACKUP" "$BACKUP.sha256" "$ENC" "$(bk_manifest_path_for_backup "$BACKUP")" 2>/dev/null || true
}
trap 'cleanup_partial' ERR

if bk_mock_enabled; then
  cat >"$BACKUP" <<EOF
-- MySQL dump mock
-- Host: 127.0.0.1    Database: ${BK_V21_TARGET_DB}
CREATE DATABASE \`${BK_V21_TARGET_DB}\`;
USE \`${BK_V21_TARGET_DB}\`;
CREATE TABLE flyway_schema_history (version varchar(50));
CREATE TABLE transactions (id bigint);
INSERT INTO flyway_schema_history VALUES ('20');
EOF
else
  [[ "${BK_V21_ALLOW_LIVE_MYSQL:-0}" == "1" ]] \
    || bk_die "live mysqldump disabled without BK_V21_ALLOW_LIVE_MYSQL=1"
  cnf="${BK_V21_MYSQL_DEFAULTS_FILE:-}"
  [[ -n "$cnf" && -f "$cnf" ]] || bk_die "BK_V21_MYSQL_DEFAULTS_FILE required"
  "$(bk_mysqldump_bin)" --defaults-file="$cnf" --protocol=TCP -h127.0.0.1 \
    --single-transaction --routines --events --triggers \
    --databases "$BK_V21_TARGET_DB" >"$BACKUP"
fi

[[ -s "$BACKUP" ]] || bk_die "backup empty"
grep -q "Database: ${BK_V21_TARGET_DB}" "$BACKUP" || bk_die "backup header missing target database"
grep -q "flyway_schema_history" "$BACKUP" || bk_die "backup missing flyway_schema_history"
grep -q "CREATE TABLE" "$BACKUP" || bk_die "backup missing CREATE TABLE"
bk_assert_no_forbidden_substrings "backup contents" "$(grep -E 'Database:|USE |CREATE DATABASE' "$BACKUP" | tr '\n' ' ')"

SHA="$(bk_sha256_file "$BACKUP")"
printf '%s  %s\n' "$SHA" "$(basename "$BACKUP")" >"$BACKUP.sha256"
[[ "$(bk_sha256_file "$BACKUP")" == "$SHA" ]] || bk_die "SHA recompute mismatch"

# Independent encrypted copy (passphrase via env; not printed)
gzip -c "$BACKUP" | openssl enc -aes-256-cbc -pbkdf2 -salt \
  -pass env:BK_V21_BACKUP_PASSPHRASE -out "$ENC"
[[ -s "$ENC" ]] || bk_die "encrypted copy empty"

# Restore feasibility without touching live DB: decrypt to temp and compare SHA
TMPDIR_FEAS="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_FEAS"; cleanup_partial' ERR
openssl enc -d -aes-256-cbc -pbkdf2 -pass env:BK_V21_BACKUP_PASSPHRASE -in "$ENC" \
  | gunzip >"$TMPDIR_FEAS/restored.sql"
[[ "$(bk_sha256_file "$TMPDIR_FEAS/restored.sql")" == "$SHA" ]] \
  || bk_die "decrypt/gunzip feasibility SHA mismatch"
rm -rf "$TMPDIR_FEAS"
trap 'cleanup_partial' ERR

bk_write_backup_manifest "$BACKUP" "$SHA" "$ENC"
MANIFEST="$(bk_manifest_path_for_backup "$BACKUP")"
# Binding self-check before confirm
bk_assert_backup_binding "$BACKUP" "$SHA" "$MANIFEST"
trap - ERR

bk_log "backup ok bytes=$(wc -c <"$BACKUP" | tr -d ' ') sha=$SHA enc=$(basename "$ENC")"
bk_require_confirm "BACKUP-OK" "Type BACKUP-OK to mark backup usable for upgrade:"
bk_log "BACKUP_OK path=$BACKUP"
# Print only non-secret paths for the next step
printf 'BACKUP_FILE=%s\nBACKUP_SHA256=%s\nBACKUP_ENC=%s\nBACKUP_MANIFEST=%s\n' \
  "$BACKUP" "$SHA" "$ENC" "$MANIFEST"
