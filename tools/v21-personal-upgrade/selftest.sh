#!/usr/bin/env bash
# Mock/synthetic self-test for V21 upgrade safety scripts.
# Does not connect to real personal ledger DBs. Does not clean drill DBs.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
source "$ROOT/lib.sh"

bk_require_cmd git
PASS=0
FAIL=0

expect_fail() {
  local name="$1"
  shift
  if "$@" >/tmp/bk-v21-st-out.txt 2>/tmp/bk-v21-st-err.txt; then
    echo "FAIL $name (expected non-zero)" >&2
    cat /tmp/bk-v21-st-err.txt >&2 || true
    FAIL=$((FAIL + 1))
  else
    echo "PASS $name" >&2
    PASS=$((PASS + 1))
  fi
}

expect_pass() {
  local name="$1"
  shift
  if "$@" >/tmp/bk-v21-st-out.txt 2>/tmp/bk-v21-st-err.txt; then
    echo "PASS $name" >&2
    PASS=$((PASS + 1))
  else
    echo "FAIL $name (expected zero)" >&2
    cat /tmp/bk-v21-st-err.txt >&2 || true
    FAIL=$((FAIL + 1))
  fi
}

write_mysql_identity() {
  local path="$1" host="$2" port="$3" uuid="$4" db="$5"
  printf '%s\n%s\n%s\n%s\n' "$host" "$port" "$uuid" "$db" >"$path"
}

TEST_ROOT="${BK_V21_SELFTEST_ROOT:-/tmp/bookkeeping-v21-upgrade-script-test}"
RC02_WT="$TEST_ROOT/rc02-wt"
OLD_WT="$TEST_ROOT/old-wt"
RC02_SHA="$BK_V21_EXPECTED_NEW_SHA"
# Parent of V21 introduction — must not contain V21 file.
OLD_FIXED_SHA="7281d1f156924e92eebc11f1843059ba41eb5f26"
DOCS_WT="/Users/limingyuan/Documents/code/personal/bookkeeping-v11-s1"
REPO_GIT="$(git -C "$DOCS_WT" rev-parse --show-toplevel)"
TARGET_DB="$BK_V21_TARGET_DB"

prepare_trees() {
  mkdir -p "$TEST_ROOT"
  if [[ ! -d "$RC02_WT/.git" && ! -f "$RC02_WT/.git" ]]; then
    rm -rf "$RC02_WT"
    git -C "$REPO_GIT" worktree add --detach "$RC02_WT" "$RC02_SHA"
  fi
  if [[ ! -d "$OLD_WT/.git" && ! -f "$OLD_WT/.git" ]]; then
    rm -rf "$OLD_WT"
    git -C "$REPO_GIT" worktree add --detach "$OLD_WT" "$OLD_FIXED_SHA"
  fi
  [[ "$(git -C "$RC02_WT" rev-parse HEAD)" == "$RC02_SHA" ]] \
    || bk_die "rc02-wt HEAD mismatch"
  [[ "$(git -C "$OLD_WT" rev-parse HEAD)" == "$OLD_FIXED_SHA" ]] \
    || bk_die "old-wt HEAD mismatch"
  [[ ! -f "$OLD_WT/$BK_V21_V21_RELPATH" ]] || bk_die "old-wt unexpectedly has V21"
}

prepare_trees
OLD_SHA="$(git -C "$OLD_WT" rev-parse HEAD)"

WORK="$(mktemp -d)"
MOCK="$WORK/mock"
mkdir -p "$MOCK" "$WORK/backup" "$WORK/enc" "$WORK/envs"
printf '20\n' >"$MOCK/flyway_max.txt"
printf '0\n' >"$MOCK/counterparty_col.txt"
printf '%s\n' "$TARGET_DB" >"$MOCK/schema_exists.txt"
GOOD_UUID="11111111-2222-3333-4444-555555555555"
write_mysql_identity "$MOCK/mysql_identity.txt" "127.0.0.1" "3306" "$GOOD_UUID" "$TARGET_DB"

write_env() {
  local path="$1" url="$2"
  umask 077
  cat >"$path" <<EOF
DB_URL=${url}
DB_USERNAME=synth_user
DB_PASSWORD=not-a-real-secret-for-git
SERVER_PORT=18090
BK_V21_BACKUP_PASSPHRASE=test-only-passphrase
EOF
  chmod 600 "$path"
}

GOOD_ENV="$WORK/envs/good.env"
write_env "$GOOD_ENV" "jdbc:mysql://127.0.0.1:3306/${TARGET_DB}?useSSL=false"
LEGACY_BOOKKEEPING_ENV="$WORK/envs/legacy-bookkeeping.env"
write_env "$LEGACY_BOOKKEEPING_ENV" 'jdbc:mysql://127.0.0.1:3306/bookkeeping?useSSL=false'
BAD_DB_ENV="$WORK/envs/bad-db.env"
write_env "$BAD_DB_ENV" 'jdbc:mysql://127.0.0.1:3306/bookkeeping_uat?useSSL=false'
BAD_TEST_ENV="$WORK/envs/bad-test.env"
write_env "$BAD_TEST_ENV" 'jdbc:mysql://127.0.0.1:3306/bookkeeping_test?useSSL=false'
BAD_DRILL_ENV="$WORK/envs/bad-drill.env"
write_env "$BAD_DRILL_ENV" 'jdbc:mysql://127.0.0.1:3306/bookkeeping_v21_restore_drill?useSSL=false'
BAD_PORT_JDBC_ENV="$WORK/envs/bad-port-jdbc.env"
write_env "$BAD_PORT_JDBC_ENV" "jdbc:mysql://127.0.0.1:3307/${TARGET_DB}?useSSL=false"

export BK_V21_MOCK=1
export BK_V21_MOCK_DIR="$MOCK"

# 0 legacy / wrong formal names
export BK_V21_CONFIRM_STDIN=PREFLIGHT-OK
expect_fail "reject-legacy-bookkeeping-db" \
  "$ROOT/preflight.sh" --env "$LEGACY_BOOKKEEPING_ENV" --new-wt "$RC02_WT" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" --port 18090

expect_fail "reject-uat-db" \
  "$ROOT/preflight.sh" --env "$BAD_DB_ENV" --new-wt "$RC02_WT" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" --port 18090

expect_fail "reject-test-db" \
  "$ROOT/preflight.sh" --env "$BAD_TEST_ENV" --new-wt "$RC02_WT" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" --port 18090

expect_fail "reject-drill-db" \
  "$ROOT/preflight.sh" --env "$BAD_DRILL_ENV" --new-wt "$RC02_WT" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" --port 18090

# 2 docs HEAD is not RC-02
expect_fail "reject-docs-head-as-new" \
  "$ROOT/preflight.sh" --env "$GOOD_ENV" --new-wt "$DOCS_WT" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" --port 18090

# 3 wrong old sha
expect_fail "reject-wrong-old-sha" \
  "$ROOT/preflight.sh" --env "$GOOD_ENV" --new-wt "$RC02_WT" --old-wt "$OLD_WT" --old-sha "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef" --port 18090

# 4 forbidden port
expect_fail "reject-forbidden-port" \
  "$ROOT/preflight.sh" --env "$GOOD_ENV" --new-wt "$RC02_WT" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" --port 18082

# 5 wrong flyway
printf '19\n' >"$MOCK/flyway_max.txt"
expect_fail "reject-flyway-not-20" \
  "$ROOT/preflight.sh" --env "$GOOD_ENV" --new-wt "$RC02_WT" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" --port 18090
printf '20\n' >"$MOCK/flyway_max.txt"

# 6 column already present
printf '1\n' >"$MOCK/counterparty_col.txt"
expect_fail "reject-column-exists" \
  "$ROOT/preflight.sh" --env "$GOOD_ENV" --new-wt "$RC02_WT" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" --port 18090
printf '0\n' >"$MOCK/counterparty_col.txt"

# 7 missing confirm
unset BK_V21_CONFIRM_STDIN
expect_fail "reject-missing-confirm" \
  "$ROOT/preflight.sh" --env "$GOOD_ENV" --new-wt "$RC02_WT" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" --port 18090

# 8 MySQL @@port != JDBC port (Spring/MySQL instance mismatch)
write_mysql_identity "$MOCK/mysql_identity.txt" "127.0.0.1" "3307" "$GOOD_UUID" "$TARGET_DB"
export BK_V21_CONFIRM_STDIN=PREFLIGHT-OK
expect_fail "reject-mysql-jdbc-port-mismatch" \
  "$ROOT/preflight.sh" --env "$GOOD_ENV" --new-wt "$RC02_WT" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" --port 18090
write_mysql_identity "$MOCK/mysql_identity.txt" "127.0.0.1" "3306" "$GOOD_UUID" "$TARGET_DB"

# 9 MySQL DATABASE() != target (even if JDBC string looked ok)
write_mysql_identity "$MOCK/mysql_identity.txt" "127.0.0.1" "3306" "$GOOD_UUID" "other_local_schema"
expect_fail "reject-mysql-database-identity-mismatch" \
  "$ROOT/preflight.sh" --env "$GOOD_ENV" --new-wt "$RC02_WT" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" --port 18090
write_mysql_identity "$MOCK/mysql_identity.txt" "127.0.0.1" "3306" "$GOOD_UUID" "$TARGET_DB"

# 10 JDBC port differs from mock MySQL while URL uses alternate port (exact target name)
export BK_V21_CONFIRM_STDIN=PREFLIGHT-OK
expect_fail "reject-jdbc-port-vs-mysql-identity" \
  "$ROOT/preflight.sh" --env "$BAD_PORT_JDBC_ENV" --new-wt "$RC02_WT" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" --port 18090

# 11 missing identity fixture
rm -f "$MOCK/mysql_identity.txt"
expect_fail "reject-missing-mysql-identity-evidence" \
  "$ROOT/preflight.sh" --env "$GOOD_ENV" --new-wt "$RC02_WT" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" --port 18090
write_mysql_identity "$MOCK/mysql_identity.txt" "127.0.0.1" "3306" "$GOOD_UUID" "$TARGET_DB"

# 12 happy preflight (bk_personal_local)
export BK_V21_CONFIRM_STDIN=PREFLIGHT-OK
expect_pass "preflight-ok-bk-personal-local" \
  "$ROOT/preflight.sh" --env "$GOOD_ENV" --new-wt "$RC02_WT" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" --port 18090

# 13 backup without passphrase
NO_PASS="$WORK/envs/nopass.env"
umask 077
cat >"$NO_PASS" <<EOF
DB_URL=jdbc:mysql://127.0.0.1:3306/${TARGET_DB}?useSSL=false
DB_USERNAME=synth_user
DB_PASSWORD=x
SERVER_PORT=18090
EOF
chmod 600 "$NO_PASS"
export BK_V21_CONFIRM_STDIN=BACKUP-OK
expect_fail "reject-backup-without-passphrase" \
  "$ROOT/backup.sh" --env "$NO_PASS" --backup-dir "$WORK/backup" --encrypt-dir "$WORK/enc"

# 14 backup ok (+ manifest)
export BK_V21_CONFIRM_STDIN=BACKUP-OK
expect_pass "backup-ok" \
  "$ROOT/backup.sh" --env "$GOOD_ENV" --backup-dir "$WORK/backup" --encrypt-dir "$WORK/enc"
BACKUP_SHA="$(grep '^BACKUP_SHA256=' /tmp/bk-v21-st-out.txt | cut -d= -f2)"
BACKUP_FILE="$(grep '^BACKUP_FILE=' /tmp/bk-v21-st-out.txt | cut -d= -f2-)"
BACKUP_MANIFEST="$(grep '^BACKUP_MANIFEST=' /tmp/bk-v21-st-out.txt | cut -d= -f2-)"
[[ -n "$BACKUP_SHA" && -n "$BACKUP_FILE" && -f "$BACKUP_FILE" && -f "$BACKUP_MANIFEST" ]] \
  || bk_die "selftest could not capture backup/manifest outputs"
grep -q "target_db=${TARGET_DB}" "$BACKUP_MANIFEST" \
  || bk_die "manifest target_db must be ${TARGET_DB}"
case "$(basename "$BACKUP_FILE")" in
  "${TARGET_DB}"-pre-v21-*.sql) ;;
  *) bk_die "backup basename must be ${TARGET_DB}-pre-v21-*.sql (got $(basename "$BACKUP_FILE"))" ;;
esac
printf '20\n' >"$MOCK/flyway_max.txt"
printf '0\n' >"$MOCK/counterparty_col.txt"

# 15 upgrade without confirm
unset BK_V21_CONFIRM_STDIN
expect_fail "reject-upgrade-without-confirm" \
  "$ROOT/upgrade-run.sh" --env "$GOOD_ENV" --new-wt "$RC02_WT" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" \
  --port 18090 --backup-file "$BACKUP_FILE" --backup-sha "$BACKUP_SHA"

# 16 upgrade without backup file
export BK_V21_CONFIRM_STDIN=UPGRADE-BOOKKEEPING
expect_fail "reject-upgrade-missing-backup-file" \
  "$ROOT/upgrade-run.sh" --env "$GOOD_ENV" --new-wt "$RC02_WT" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" \
  --port 18090 --backup-sha "$BACKUP_SHA"

# 17 backup SHA mismatch
expect_fail "reject-upgrade-sha-mismatch" \
  "$ROOT/upgrade-run.sh" --env "$GOOD_ENV" --new-wt "$RC02_WT" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" \
  --port 18090 --backup-file "$BACKUP_FILE" --backup-sha "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

# 18 tampered backup bytes (SHA vs manifest)
TAMPER="$WORK/backup/tampered.sql"
cp "$BACKUP_FILE" "$TAMPER"
cp "$BACKUP_MANIFEST" "$TAMPER.manifest"
echo "-- tampered" >>"$TAMPER"
expect_fail "reject-tampered-backup-bytes" \
  "$ROOT/upgrade-run.sh" --env "$GOOD_ENV" --new-wt "$RC02_WT" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" \
  --port 18090 --backup-file "$TAMPER" --backup-sha "$BACKUP_SHA" --manifest "$TAMPER.manifest"

# 19 manifest points at wrong target / uuid
BAD_MF="$WORK/backup/bad.manifest"
cp "$BACKUP_MANIFEST" "$BAD_MF"
python3 - "$BAD_MF" <<'PY'
import pathlib,sys
p=pathlib.Path(sys.argv[1])
lines=[]
for line in p.read_text().splitlines():
    if line.startswith("mysql_server_uuid="):
        lines.append("mysql_server_uuid=00000000-0000-0000-0000-000000000000")
    else:
        lines.append(line)
p.write_text("\n".join(lines)+"\n")
PY
chmod 600 "$BAD_MF"
expect_fail "reject-manifest-wrong-instance-uuid" \
  "$ROOT/upgrade-run.sh" --env "$GOOD_ENV" --new-wt "$RC02_WT" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" \
  --port 18090 --backup-file "$BACKUP_FILE" --backup-sha "$BACKUP_SHA" --manifest "$BAD_MF"

# 19b manifest target_db mismatch (legacy name)
BAD_MF_DB="$WORK/backup/bad-db.manifest"
cp "$BACKUP_MANIFEST" "$BAD_MF_DB"
python3 - "$BAD_MF_DB" <<'PY'
import pathlib,sys
p=pathlib.Path(sys.argv[1])
lines=[]
for line in p.read_text().splitlines():
    if line.startswith("target_db="):
        lines.append("target_db=bookkeeping")
    elif line.startswith("mysql_database="):
        lines.append("mysql_database=bookkeeping")
    else:
        lines.append(line)
p.write_text("\n".join(lines)+"\n")
PY
chmod 600 "$BAD_MF_DB"
expect_fail "reject-manifest-legacy-target-db" \
  "$ROOT/upgrade-run.sh" --env "$GOOD_ENV" --new-wt "$RC02_WT" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" \
  --port 18090 --backup-file "$BACKUP_FILE" --backup-sha "$BACKUP_SHA" --manifest "$BAD_MF_DB"

# 20 missing manifest
expect_fail "reject-missing-manifest" \
  "$ROOT/upgrade-run.sh" --env "$GOOD_ENV" --new-wt "$RC02_WT" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" \
  --port 18090 --backup-file "$BACKUP_FILE" --backup-sha "$BACKUP_SHA" --manifest "$WORK/backup/no-such.manifest"

# 21 upgrade dry with confirm + binding
export BK_V21_CONFIRM_STDIN=UPGRADE-BOOKKEEPING
expect_pass "upgrade-dry-ok" \
  "$ROOT/upgrade-run.sh" --env "$GOOD_ENV" --new-wt "$RC02_WT" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" \
  --port 18090 --backup-file "$BACKUP_FILE" --backup-sha "$BACKUP_SHA"

# 22 restore dry against mocked V21 without blank approval
printf '21\n' >"$MOCK/flyway_max.txt"
printf '1\n' >"$MOCK/counterparty_col.txt"
unset BK_V21_ALLOW_BLANK_RESTORE
expect_fail "reject-restore-v21-without-blank-flag" \
  "$ROOT/restore.sh" --env "$GOOD_ENV" --backup "$BACKUP_FILE" --backup-sha "$BACKUP_SHA" --old-wt "$OLD_WT" --old-sha "$OLD_SHA"

# 23 restore dry with blank flag allowed (still dry)
export BK_V21_ALLOW_BLANK_RESTORE=1
expect_pass "restore-dry-with-blank-flag" \
  "$ROOT/restore.sh" --env "$GOOD_ENV" --backup "$BACKUP_FILE" --backup-sha "$BACKUP_SHA" --old-wt "$OLD_WT" --old-sha "$OLD_SHA"

# 24 execute without arming switches still fails
export BK_V21_CONFIRM_STDIN=RESTORE-BOOKKEEPING-FULL
expect_fail "reject-restore-execute-unarmed" \
  "$ROOT/restore.sh" --env "$GOOD_ENV" --backup "$BACKUP_FILE" --backup-sha "$BACKUP_SHA" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" --execute

# 25 restore rejects when identity evidence gone
rm -f "$MOCK/mysql_identity.txt"
expect_fail "reject-restore-without-identity" \
  "$ROOT/restore.sh" --env "$GOOD_ENV" --backup "$BACKUP_FILE" --backup-sha "$BACKUP_SHA" --old-wt "$OLD_WT" --old-sha "$OLD_SHA"

rm -rf "$WORK"
echo "SELFTEST_PASS=$PASS SELFTEST_FAIL=$FAIL" >&2
[[ "$FAIL" -eq 0 ]]
