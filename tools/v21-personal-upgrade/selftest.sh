#!/usr/bin/env bash
# Mock/synthetic self-test for V21 upgrade safety scripts.
# Does not connect to real bookkeeping. Does not clean drill DBs.
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

TEST_ROOT="${BK_V21_SELFTEST_ROOT:-/tmp/bookkeeping-v21-upgrade-script-test}"
RC02_WT="$TEST_ROOT/rc02-wt"
OLD_WT="$TEST_ROOT/old-wt"
[[ -d "$RC02_WT" ]] || bk_die "missing RC02 test tree: $RC02_WT (create worktree first)"
[[ -d "$OLD_WT" ]] || bk_die "missing OLD test tree: $OLD_WT"
OLD_SHA="$(git -C "$OLD_WT" rev-parse HEAD)"
DOCS_WT="/Users/limingyuan/Documents/code/personal/bookkeeping-v11-s1"

WORK="$(mktemp -d)"
MOCK="$WORK/mock"
mkdir -p "$MOCK" "$WORK/backup" "$WORK/enc" "$WORK/envs"
printf '20\n' >"$MOCK/flyway_max.txt"
printf '0\n' >"$MOCK/counterparty_col.txt"
printf 'bookkeeping\n' >"$MOCK/schema_exists.txt"

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
write_env "$GOOD_ENV" 'jdbc:mysql://127.0.0.1:3306/bookkeeping?useSSL=false'
BAD_DB_ENV="$WORK/envs/bad-db.env"
write_env "$BAD_DB_ENV" 'jdbc:mysql://127.0.0.1:3306/bookkeeping_uat?useSSL=false'
BAD_TEST_ENV="$WORK/envs/bad-test.env"
write_env "$BAD_TEST_ENV" 'jdbc:mysql://127.0.0.1:3306/bookkeeping_test?useSSL=false'

export BK_V21_MOCK=1
export BK_V21_MOCK_DIR="$MOCK"

# 1 wrong database name
export BK_V21_CONFIRM_STDIN=PREFLIGHT-OK
expect_fail "reject-uat-db" \
  "$ROOT/preflight.sh" --env "$BAD_DB_ENV" --new-wt "$RC02_WT" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" --port 18090

expect_fail "reject-test-db" \
  "$ROOT/preflight.sh" --env "$BAD_TEST_ENV" --new-wt "$RC02_WT" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" --port 18090

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

# 8 happy preflight
export BK_V21_CONFIRM_STDIN=PREFLIGHT-OK
expect_pass "preflight-ok" \
  "$ROOT/preflight.sh" --env "$GOOD_ENV" --new-wt "$RC02_WT" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" --port 18090

# 9 backup without passphrase
NO_PASS="$WORK/envs/nopass.env"
umask 077
cat >"$NO_PASS" <<EOF
DB_URL=jdbc:mysql://127.0.0.1:3306/bookkeeping?useSSL=false
DB_USERNAME=synth_user
DB_PASSWORD=x
SERVER_PORT=18090
EOF
chmod 600 "$NO_PASS"
export BK_V21_CONFIRM_STDIN=BACKUP-OK
expect_fail "reject-backup-without-passphrase" \
  "$ROOT/backup.sh" --env "$NO_PASS" --backup-dir "$WORK/backup" --encrypt-dir "$WORK/enc"

# 10 backup ok
export BK_V21_CONFIRM_STDIN=BACKUP-OK
expect_pass "backup-ok" \
  "$ROOT/backup.sh" --env "$GOOD_ENV" --backup-dir "$WORK/backup" --encrypt-dir "$WORK/enc"
BACKUP_SHA="$(grep '^BACKUP_SHA256=' /tmp/bk-v21-st-out.txt | cut -d= -f2)"
BACKUP_FILE="$(grep '^BACKUP_FILE=' /tmp/bk-v21-st-out.txt | cut -d= -f2-)"
[[ -n "$BACKUP_SHA" && -n "$BACKUP_FILE" && -f "$BACKUP_FILE" ]] || bk_die "selftest could not capture backup outputs"
# reset schema mock to V20 for upgrade path
printf '20\n' >"$MOCK/flyway_max.txt"
printf '0\n' >"$MOCK/counterparty_col.txt"

# 11 upgrade without confirm
unset BK_V21_CONFIRM_STDIN
expect_fail "reject-upgrade-without-confirm" \
  "$ROOT/upgrade-run.sh" --env "$GOOD_ENV" --new-wt "$RC02_WT" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" --port 18090 --backup-sha "$BACKUP_SHA"

# 12 upgrade dry with confirm
export BK_V21_CONFIRM_STDIN=UPGRADE-BOOKKEEPING
expect_pass "upgrade-dry-ok" \
  "$ROOT/upgrade-run.sh" --env "$GOOD_ENV" --new-wt "$RC02_WT" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" --port 18090 --backup-sha "$BACKUP_SHA"

# 13 restore dry against mocked V21 without blank approval
printf '21\n' >"$MOCK/flyway_max.txt"
printf '1\n' >"$MOCK/counterparty_col.txt"
unset BK_V21_ALLOW_BLANK_RESTORE
expect_fail "reject-restore-v21-without-blank-flag" \
  "$ROOT/restore.sh" --env "$GOOD_ENV" --backup "$BACKUP_FILE" --backup-sha "$BACKUP_SHA" --old-wt "$OLD_WT" --old-sha "$OLD_SHA"

# 14 restore dry with blank flag allowed (still dry)
export BK_V21_ALLOW_BLANK_RESTORE=1
expect_pass "restore-dry-with-blank-flag" \
  "$ROOT/restore.sh" --env "$GOOD_ENV" --backup "$BACKUP_FILE" --backup-sha "$BACKUP_SHA" --old-wt "$OLD_WT" --old-sha "$OLD_SHA"

# 15 execute without arming switches still fails
export BK_V21_CONFIRM_STDIN=RESTORE-BOOKKEEPING-FULL
expect_fail "reject-restore-execute-unarmed" \
  "$ROOT/restore.sh" --env "$GOOD_ENV" --backup "$BACKUP_FILE" --backup-sha "$BACKUP_SHA" --old-wt "$OLD_WT" --old-sha "$OLD_SHA" --execute
rm -rf "$WORK"
echo "SELFTEST_PASS=$PASS SELFTEST_FAIL=$FAIL" >&2
[[ "$FAIL" -eq 0 ]]
