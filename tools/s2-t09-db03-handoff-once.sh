#!/bin/bash
# S2-T09 DB-03 one-round Coding -> Supervisor handoff. max_rounds=1.
set -Eeuo pipefail

ROOT="/Users/limingyuan/Documents/code/personal/bookkeeping-s2-t00"
PRIMARY="/Users/limingyuan/Documents/code/personal/bookkeeping"
EVIDENCE="/Users/limingyuan/Documents/code/personal/bookkeeping-s2-ab-lite/evidence/s2-t09-db03-handoff"
EXPECTED_HEAD="5c6cd7ad91d183999a7a6c177a2a03e1e8e37dd9"
PLAN="docs/implementation/bookkeeping-v1.0-stage-s2-plan-v0.1-approved.md"
PLAN_SHA="69ff73a1c0f549cccd125d5b89271996076ab8685a4fe70745c91beb26561be5"
V16="backend/src/main/resources/db/migration/V16__create_transfers.sql"
V16_SHA="24c286fc5ef44f4581c0e21c35db4c54e02427f2b1d49f276a399e2ee81c9f9f"
ADJ="docs/implementation/s2-t08-t09-adj-001.md"
ADJ_SHA="f6e51b3ed178f1703d300cb7c249c8b93ebddb00815b53a8352fab10c4154c3d"
DB03="docs/implementation/s2-db-03-approval-2026-10-23.md"
DB03_SHA="ccd78a4d88099d0f844faa84e5ea662c70f41e1f9254959cb3f849123e9c5689"
BRANCH="task/s2-t00-t01"
JAVA_HOME_BOOTSTRAP="$HOME/.local/share/bookkeeping-bootstrap/zulu25.36.205-ca-jdk25.0.4.1-macosx_aarch64/Contents/Home"
NODE_BIN="$HOME/.nvm/versions/node/v22.23.3/bin"
export JAVA_HOME="$JAVA_HOME_BOOTSTRAP"
export PATH="$JAVA_HOME/bin:$HOME/.local/bin:$NODE_BIN:/usr/bin:/bin:/usr/sbin:/sbin:/usr/local/bin:/opt/homebrew/bin:$PATH"

mkdir -p "$EVIDENCE"
exec > >(tee -a "$EVIDENCE/run.log") 2>&1

fail() {
  printf 'STATUS: %s\n' "$1" | tee "$EVIDENCE/STATUS.txt"
  echo "BLOCKED: $*" >&2
  exit 1
}
g() { git -c core.fsmonitor=false "$@"; }
sha() { shasum -a 256 "$1" | awk '{print $1}'; }

cd "$ROOT"
echo "=== 1. Identity ==="
[[ "$(g branch --show-current)" == "$BRANCH" ]] || fail "branch"
[[ "$(g rev-parse HEAD)" == "$EXPECTED_HEAD" ]] || fail "HEAD is not the T09 UI baseline"
DIRTY="$(g status --porcelain)"
[[ -z "$DIRTY" || "$DIRTY" == "?? tools/s2-t09-db03-handoff-once.sh" ]] || fail "worktree is not clean"
[[ "$(sha "$PLAN")" == "$PLAN_SHA" ]] || fail "plan SHA"
[[ "$(sha "$V16")" == "$V16_SHA" ]] || fail "V16 SHA"
[[ "$(sha "$ADJ")" == "$ADJ_SHA" ]] || fail "adjustment record SHA"
[[ -x "$JAVA_HOME/bin/java" ]] || fail "Bootstrap JDK missing"
AGENT="$(command -v agent || true)"
[[ -n "$AGENT" ]] || fail "agent CLI missing"
"$AGENT" status > "$EVIDENCE/agent-status.txt"
grep -q "Logged in" "$EVIDENCE/agent-status.txt" || fail "CLI is not logged in"
python3 - <<'PY'
from pathlib import Path
text = """# DB-03 批准记录

Status: Owner approved the product rule for deleting account-scoped analysis rows during permanent account deletion. This file records that approval. It does not replace the S2 plan, and it is not a database execution approval.

The approved plan file `docs/implementation/bookkeeping-v1.0-stage-s2-plan-v0.1-approved.md` stays at SHA-256 `69ff73a1c0f549cccd125d5b89271996076ab8685a4fe70745c91beb26561be5`. This record does not edit that file. `docs/implementation/s2-t08-t09-adj-001.md` stays unchanged.

## 批准事实

负责人于 2026-10-23 在 S2-T09 DB-03 任务说明中批准：永久删除归档账户时，在明确预览和用户确认后，允许同步永久删除当前用户、明确关联该账户的 AI 历史报告及生成记录。

不得删除其他账户、`account_id IS NULL` 历史报告或用户级共享记录。

要求验证并发、事务及安全。本审批不授权新增 Schema、真实或生产数据删除。

这次批准没有单独的文件 SHA。本记录不编造一个。

治理 v1.7 读自本机 `Downloads/bookkeeping-ai-collaboration-governance-v1.7.md`，SHA-256 `4fcf410092429fa97aa21baaddc91b027fa0d006cee08af59e46b49bac4c56c1`。本次没有把 v1.7 抄进仓库。

## 没有包含的授权

不新增 Schema，不修改 V14 或 V16。没有连接 `bookkeeping`、`bookkeeping_test`、`bookkeeping_migration_test`、生产库或真实业务数据，也没有执行 Flyway 或删除这些数据。

不修改已批准的 S2 计划原件。不 Push。不进入 T10/T11。
"""
path = Path("/Users/limingyuan/Documents/code/personal/bookkeeping-s2-t00/docs/implementation/s2-db-03-approval-2026-10-23.md")
path.write_text(text, encoding="utf-8")
print("db03_written", path.stat().st_size)
PY
[[ "$(sha "$DB03")" == "$DB03_SHA" ]] || fail "DB-03 record SHA"
echo "identity PASS head=$EXPECTED_HEAD"

cat > "$EVIDENCE/coding-prompt.txt" <<'PROMPT'
你是本轮唯一的 S2-T09 DB-03 Coding Agent。工作区是 /Users/limingyuan/Documents/code/personal/bookkeeping-s2-t00，分支 task/s2-t00-t01。只把已批准的 DB-03 规则补进现有永久删除后端和小程序。不要提交，不要 push，不要启动另一个 Agent，不要进入 T10/T11。

禁止：修改 db/migration、V14、V16、正式计划、docs/implementation/s2-t08-t09-adj-001.md、docs/implementation/s2-db-03-approval-2026-10-23.md；新增表或约束；连接 MySQL；运行 Flyway、@SpringBootTest 或会启动应用的测试；删除其他账户、account_id IS NULL 的分析行、analysis_quota_days、analysis_user_locks；reset --hard。

先读 AccountPermanentDeletionService、AccountPermanentDeletionPreviewToken、AccountPermanentDeletionStore、JpaAccountPermanentDeletionStore、AccountPermanentDeletionTest，以及 frontend 的 account/permanent-deletion.ts 和 pages/account-permanent-deletion。V11 中 analysis_generations.snapshot_id 引用 analysis_snapshots。V14 的账户外键只在 account_id 非空时生效。

负责人已批准的规则：永久删除归档账户时，预览和确认之后，可以同步永久删除当前用户、account_id 明确等于该账户的 analysis_snapshots 与 analysis_generations。不得删除其他账户、NULL 旧报告或用户级配额和锁。不新增 Schema。

实现：
1. 预览增加 analysisSnapshotCount 与 analysisGenerationCount，只统计当前用户且 account_id 等于目标账户的行。保留不可恢复说明，并写明这些 AI 历史报告和生成记录会一并删除。把两类 id 纳入 previewToken。token 版本改为 2，旧 token 不能通过。
2. 同一事务、按账户 id 升序锁定目标账户和转账对手之后，重新读取并校验 token。不一致则 409 PREVIEW_STALE，不删除。
3. 先删除本用户本账户的 analysis_generations，再删除本用户本账户的 analysis_snapshots，然后才是交易标签、交易、余额调整、整笔转账和账户。删除条件必须同时等于当前 user_id 和目标 account_id。不要顺着 snapshot_id 去删别的账户或别的用户的行。
4. 本账户生成记录的 snapshot_id 若指向别的用户、别的账户或 account_id IS NULL 的快照，409 ANALYSIS_REFERENCE_CONFLICT，文案「分析记录存在跨账户或跨用户引用，本次未删除任何数据」，并且不调用任何删除。本账户快照若被其他用户、其他账户或 account_id IS NULL 的生成记录引用，同样拒绝。snapshot_id 为空的本账户生成记录属于该账户，应删除，不是异常。
5. 正常的本账户分析行不再返回 ANALYSIS_RECORDS_REMAIN，也不再阻断确认。若删除过程中外键冲突或事务失败，整笔回滚，不能留下已删交易但账户或报告还在的提交。异常向调用方抛出，不要吞掉后提交。
6. 小程序展示两类数量和不可恢复提示。analysisSnapshotCount 或 analysisGenerationCount 大于 0 时仍允许确认。确认只提交 previewToken。ANALYSIS_REFERENCE_CONFLICT、PREVIEW_STALE、ACCOUNT_NOT_ARCHIVED 都不得显示为删除成功。若服务端仍返回 ANALYSIS_RECORDS_REMAIN，也不得显示成功。不要调用分析接口去改报告。
7. 其他账户、其他用户、NULL 旧报告、配额和用户锁的查询与删除都不要碰到。永久删除后仍不提供恢复。
8. 扩展 AccountPermanentDeletionTest，禁止 @SpringBootTest 和 DataSource。覆盖：本账户快照和生成记录的预览数量与整笔删除顺序；其他账户、其他用户、NULL 行和配额锁不被删除；跨账户引用拒绝且不调用删除；预览后 id 变化则拒绝；删除账户前失败时事务回滚标记为未提交；正常账户拒绝。前端测试改为允许有本账户报告时确认，并覆盖新的失败码。

完成后运行：
cd backend && ./mvnw -Dtest=AccountPermanentDeletionTest test
再在 frontend/miniprogram 运行 npm test、npm run typecheck、npm run lint。
JAVA_HOME 是 Bootstrap JDK，Node 是 ~/.nvm/versions/node/v22.23.3/bin。不要运行其他会连接数据库的测试。最终说明写真实退出码，并写明数据库集成测试 SKIPPED。不要 git commit。不要改 tools/s2-t09-db03-handoff-once.sh。
PROMPT

echo "=== 2. Coding agent ==="
python3 - <<'PY'
import signal, subprocess, os
evidence = "/Users/limingyuan/Documents/code/personal/bookkeeping-s2-ab-lite/evidence/s2-t09-db03-handoff"
root = "/Users/limingyuan/Documents/code/personal/bookkeeping-s2-t00"
agent = os.path.expanduser("~/.local/bin/agent")
prompt = open(evidence + "/coding-prompt.txt", encoding="utf-8").read()
cmd = [agent, "--print", "--trust", "--force", "--sandbox", "disabled", "--workspace", root, "--output-format", "text", prompt]
with open(evidence + "/coding-agent.txt", "w") as out:
    proc = subprocess.Popen(cmd, stdout=out, stderr=subprocess.STDOUT, start_new_session=True)
    open(evidence + "/coding-agent.pid", "w").write(str(proc.pid))
    try:
        rc = proc.wait(timeout=2700)
        print("coding_rc", rc)
    except subprocess.TimeoutExpired:
        os.killpg(proc.pid, signal.SIGKILL)
        print("coding_rc timeout")
PY

echo "=== 3. Guards after coding ==="
[[ "$(sha "$PLAN")" == "$PLAN_SHA" ]] || fail "plan SHA changed"
[[ "$(sha "$V16")" == "$V16_SHA" ]] || fail "V16 changed"
[[ "$(sha "$ADJ")" == "$ADJ_SHA" ]] || fail "adjustment record changed"
[[ "$(sha "$DB03")" == "$DB03_SHA" ]] || fail "DB-03 record changed"
if g status --porcelain | grep -E -q 'db/migration/'; then
  fail "migration file changed"
fi
HEAD_NOW="$(g rev-parse HEAD)"
echo "head_after_coding=$HEAD_NOW"
g status --porcelain > "$EVIDENCE/status-after-coding.txt" || true
if [[ "$HEAD_NOW" != "$EXPECTED_HEAD" ]]; then
  PARENT="$(g rev-parse "$HEAD_NOW^")"
  [[ "$PARENT" == "$EXPECTED_HEAD" ]] || fail "coding agent rewrote history"
fi
if ! g status --porcelain | grep -q 'backend/'; then
  printf 'STATUS: BLOCKED\n' | tee "$EVIDENCE/STATUS.txt"
  echo "coding agent made no backend change; supervisor not started"
  exit 1
fi
if ! g status --porcelain | grep -q 'frontend/'; then
  printf 'STATUS: BLOCKED\n' | tee "$EVIDENCE/STATUS.txt"
  echo "coding agent made no frontend change; supervisor not started"
  exit 1
fi

echo "=== 4. Unit and frontend checks ==="
cd "$ROOT/backend"
set +e
./mvnw -Dtest=AccountPermanentDeletionTest test > "$EVIDENCE/maven-test.log" 2>&1
MVN_RC=$?
set -e
if grep -E -q 'Started BookkeepingApplication|Flyway|HikariPool' "$EVIDENCE/maven-test.log"; then
  printf 'STATUS: FAIL\n' | tee "$EVIDENCE/STATUS.txt"
  echo "unit test touched a database; supervisor not started"
  exit 2
fi
cd "$ROOT/frontend/miniprogram"
if [[ ! -d node_modules ]]; then
  npm ci > "$EVIDENCE/npm-ci.log" 2>&1 || fail "npm ci failed"
fi
set +e
npm test > "$EVIDENCE/npm-test.log" 2>&1
TEST_RC=$?
npm run typecheck > "$EVIDENCE/npm-typecheck.log" 2>&1
TYPE_RC=$?
npm run lint > "$EVIDENCE/npm-lint.log" 2>&1
LINT_RC=$?
set -e
printf 'maven_test=%s\nintegration=SKIPPED\nnpm_test=%s\nnpm_typecheck=%s\nnpm_lint=%s\n' "$MVN_RC" "$TEST_RC" "$TYPE_RC" "$LINT_RC" | tee "$EVIDENCE/exit-codes.txt"
cd "$ROOT"
if [[ "$MVN_RC" != 0 || "$TEST_RC" != 0 || "$TYPE_RC" != 0 || "$LINT_RC" != 0 ]]; then
  printf 'STATUS: FAIL\n' | tee "$EVIDENCE/STATUS.txt"
  echo "checks failed; supervisor not started; no auto-fix"
  exit 2
fi

if [[ -n "$(g status --porcelain)" ]]; then
  g add -- backend frontend tools/s2-t09-db03-handoff-once.sh "$DB03"
  if g diff --cached --name-only | grep -E -q 'db/migration/|bookkeeping-v1.0-stage-s2-plan|s2-t08-t09-adj-001'; then
    fail "refusing to commit migration, plan, or ADJ-001"
  fi
  if g diff --cached --name-only | grep -q .; then
    g commit -m "$(cat <<'EOF'
S2-T09: delete an archived account together with its own analysis records.

EOF
)" || {
      printf 'STATUS: FAIL\n' | tee "$EVIDENCE/STATUS.txt"
      echo "commit failed; supervisor not started"
      exit 2
    }
  fi
fi
COMMIT="$(g rev-parse HEAD)"
[[ -z "$(g status --porcelain)" ]] || fail "worktree dirty after commit"
[[ "$(sha "$DB03")" == "$DB03_SHA" ]] || fail "DB-03 record SHA after commit"
echo "commit=$COMMIT" | tee "$EVIDENCE/commit.txt"
g diff --no-ext-diff "$EXPECTED_HEAD" "$COMMIT" > "$EVIDENCE/commit.diff"
sha "$EVIDENCE/commit.diff" > "$EVIDENCE/commit.diff.sha256"
printf '%s\n' "$COMMIT" > "$EVIDENCE/commit.sha"

echo "=== 5. Snapshot and supervisor ==="
SNAP="$EVIDENCE/snapshot"
rm -rf "$SNAP"
mkdir -p "$SNAP/review-evidence" "$EVIDENCE/supervisor-log"
g archive --format=tar HEAD | tar -x -C "$SNAP"
cp "$EVIDENCE/commit.diff" "$EVIDENCE/commit.diff.sha256" "$EVIDENCE/commit.sha" "$EVIDENCE/exit-codes.txt" "$SNAP/review-evidence/"
python3 - <<'PY'
import os, textwrap
evidence = "/Users/limingyuan/Documents/code/personal/bookkeeping-s2-ab-lite/evidence/s2-t09-db03-handoff"
paths = {
    "snap": os.path.realpath(evidence + "/snapshot"),
    "log": os.path.realpath(evidence + "/supervisor-log"),
    "root": os.path.realpath("/Users/limingyuan/Documents/code/personal/bookkeeping-s2-t00"),
    "primary": os.path.realpath("/Users/limingyuan/Documents/code/personal/bookkeeping"),
    "cursor": os.path.realpath(os.path.expanduser("~/.cursor")),
    "agent": os.path.realpath(os.path.expanduser("~/.local/share/cursor-agent")),
    "caches": os.path.realpath(os.path.expanduser("~/Library/Caches")),
}
profile = textwrap.dedent(f"""
(version 1)
(deny default)
(allow file-read*)
(allow file-write* (subpath "{paths['log']}"))
(allow file-write* (subpath "{paths['cursor']}"))
(allow file-write* (subpath "{paths['agent']}"))
(allow file-write* (subpath "{paths['caches']}"))
(allow file-write* (subpath "/private/var/folders"))
(allow file-write* (literal "/dev/null"))
(allow process*)
(allow signal)
(allow sysctl-read)
(allow mach*)
(allow ipc*)
(allow network*)
(allow system-socket)
(deny file-write* (subpath "{paths['snap']}"))
(deny file-write* (subpath "{paths['root']}"))
(deny file-write* (subpath "{paths['primary']}"))
""")
open("/tmp/s2-t09-db03-supervisor.sb", "w").write(profile)
print("profile_written")
PY

cat > "$EVIDENCE/supervisor-prompt.txt" <<PROMPT
你是全新的 S2-T09 DB-03 Supervisor。你不是刚才的 Coding Agent，禁止 resume。这是只读审核。

代码快照在 $SNAP。真实 diff 在 review-evidence/commit.diff，SHA-256 在 review-evidence/commit.diff.sha256，提交在 review-evidence/commit.sha。请先 shasum 核对 diff。父提交是 $EXPECTED_HEAD，本轮提交是 $COMMIT。测试日志在 $EVIDENCE/maven-test.log、npm-test.log、npm-typecheck.log、npm-lint.log 和 exit-codes.txt。

对照负责人 DB-03：只删除当前用户且 account_id 等于目标账户的 analysis_generations 与 analysis_snapshots；预览展示两类数量并纳入 token；同一事务；不顺着 snapshot_id 误删；跨账户或跨用户引用拒绝且不部分删除；NULL 旧报告、其他账户、配额和用户锁不动；正常账户仍不可删；小程序知情确认后可以删除本账户报告，失败不是成功。确认没有新 Schema、没有改 V14/V16、没有改计划原件和 ADJ-001。DB-03 记录 SHA-256 必须是 $DB03_SHA。数据库集成测试未运行，不能写成 MySQL PASS。

不要修改任何文件。不要运行 MySQL、Flyway、npm、mvn。不要 commit、push、merge。请尝试把单词 wrote 写入 $SNAP/supervisor-wrote.txt；预期被执行环境拒绝。即使写入失败也继续审核。

最终结论必须包含这些独立行：
VERDICT: PASS
或 VERDICT: FAIL
或 VERDICT: BLOCKED
P0: <整数>
P1: <整数>
P2: <整数>
P3: <整数>
审核后停止。不要修改代码，不要启动 T10。
PROMPT

python3 - <<'PY'
import os, signal, subprocess
evidence = "/Users/limingyuan/Documents/code/personal/bookkeeping-s2-ab-lite/evidence/s2-t09-db03-handoff"
snap = evidence + "/snapshot"
agent = os.path.expanduser("~/.local/bin/agent")
prompt = open(evidence + "/supervisor-prompt.txt", encoding="utf-8").read()
cmd = [
    "sandbox-exec", "-f", "/tmp/s2-t09-db03-supervisor.sb",
    agent, "--print", "--trust", "--force", "--sandbox", "disabled",
    "--workspace", snap, "--output-format", "text", prompt,
]
with open(evidence + "/supervisor-agent.txt", "w") as out:
    proc = subprocess.Popen(cmd, stdout=out, stderr=subprocess.STDOUT, start_new_session=True)
    open(evidence + "/supervisor-agent.pid", "w").write(str(proc.pid))
    try:
        rc = proc.wait(timeout=900)
        print("supervisor_rc", rc)
    except subprocess.TimeoutExpired:
        os.killpg(proc.pid, signal.SIGKILL)
        print("supervisor_rc timeout")
PY

cd "$ROOT"
AFTER="$(g rev-parse HEAD)"
DIRTY="$(g status --porcelain)"
WROTE=0
[[ -f "$SNAP/supervisor-wrote.txt" ]] && WROTE=1
printf 'head_after_supervisor=%s\ndirty=%s\nsupervisor_wrote=%s\n' "$AFTER" "${DIRTY:-clean}" "$WROTE" | tee "$EVIDENCE/isolation.txt"
if [[ "$AFTER" != "$COMMIT" || -n "$DIRTY" || "$WROTE" != 0 ]]; then
  printf 'STATUS: BLOCKED\n' | tee "$EVIDENCE/STATUS.txt"
  echo "supervisor isolation failed"
  exit 3
fi
printf 'STATUS: REVIEWED\n' | tee "$EVIDENCE/STATUS.txt"
echo "handoff complete; no further coding"
