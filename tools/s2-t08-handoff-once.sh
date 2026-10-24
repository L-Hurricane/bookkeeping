#!/bin/bash
# S2-T08 one-round Coding -> Supervisor handoff. max_rounds=1.
# Reuses the T07 seatbelt and snapshot pattern. Does not push, migrate, or start T09.
set -Eeuo pipefail

ROOT="/Users/limingyuan/Documents/code/personal/bookkeeping-s2-t00"
PRIMARY="/Users/limingyuan/Documents/code/personal/bookkeeping"
EVIDENCE="/Users/limingyuan/Documents/code/personal/bookkeeping-s2-ab-lite/evidence/s2-t08-handoff"
EXPECTED_HEAD="41d98a3dfc7d06eb51be860c120ab93b55bd2e5e"
PLAN="docs/implementation/bookkeeping-v1.0-stage-s2-plan-v0.1-approved.md"
PLAN_SHA="69ff73a1c0f549cccd125d5b89271996076ab8685a4fe70745c91beb26561be5"
V16="backend/src/main/resources/db/migration/V16__create_transfers.sql"
V16_SHA="24c286fc5ef44f4581c0e21c35db4c54e02427f2b1d49f276a399e2ee81c9f9f"
BRANCH="task/s2-t00-t01"
JAVA_HOME_BOOTSTRAP="$HOME/.local/share/bookkeeping-bootstrap/zulu25.36.205-ca-jdk25.0.4.1-macosx_aarch64/Contents/Home"
export JAVA_HOME="$JAVA_HOME_BOOTSTRAP"
export PATH="$JAVA_HOME/bin:$HOME/.local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/usr/local/bin:/opt/homebrew/bin:$PATH"

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
[[ "$(g rev-parse HEAD)" == "$EXPECTED_HEAD" ]] || fail "HEAD is not the T07 baseline"
DIRTY="$(g status --porcelain)"
[[ -z "$DIRTY" || "$DIRTY" == "?? tools/s2-t08-handoff-once.sh" ]] || fail "worktree is not clean"
[[ "$(sha "$PLAN")" == "$PLAN_SHA" ]] || fail "plan SHA"
[[ "$(sha "$V16")" == "$V16_SHA" ]] || fail "V16 SHA"
[[ -x "$JAVA_HOME/bin/java" ]] || fail "Bootstrap JDK missing"
AGENT="$(command -v agent || true)"
[[ -n "$AGENT" ]] || fail "agent CLI missing"
"$AGENT" status > "$EVIDENCE/agent-status.txt"
grep -q "Logged in" "$EVIDENCE/agent-status.txt" || fail "CLI is not logged in"
echo "identity PASS head=$EXPECTED_HEAD"

cat > "$EVIDENCE/coding-prompt.txt" <<'PROMPT'
你是本轮唯一的 S2-T08 Coding Agent。工作区是 /Users/limingyuan/Documents/code/personal/bookkeeping-s2-t00，分支 task/s2-t00-t01。只实现归档账户永久删除的后端和影响预览。不要提交，不要 push，不要启动另一个 Agent，不要修改小程序，不要进入 T09/T10/T11。

组长本轮要求把永久删除后端放在 T08 完成。正式计划原文仍把 T08 写成只读审查、把编码放在 T09。不要改计划文件。不要实现小程序。不要新增 DDL。

禁止：连接 MySQL；运行 Flyway；运行任何 @SpringBootTest 或会启动应用的测试；修改 db/migration 下任何文件，包括 V14 和 V16；删除、清空或改写 analysis_snapshots、analysis_generations 及其他分析表；reset --hard；清理工作区；做数据恢复或审计平台。

先读：
- docs/product/bookkeeping-v1.0-product-baseline-v1.0.md 的 AC-07～AC-10、TRF-06、BAL-04
- backend AccountController、AccountService、CurrentBalance
- backend TransferService 的锁顺序和整笔删除
- V6、V7、V9、V14、V16 里指向 accounts 的外键。V7 transaction_tags 对 transactions 是 ON DELETE CASCADE。V14 分析表的 account_id 外键没有获批的删除、保留或失效语义。

实现：
1. 只有 lifecycle=ARCHIVED 的当前用户账户可以预览和执行永久删除。正常账户拒绝，错误码 ACCOUNT_NOT_ARCHIVED，文案「只有已归档账户可以永久删除」。其他用户的账户返回 404 RESOURCE_NOT_FOUND，不要用 403。
2. 预览返回：transactionCount、adjustmentCount、transferCount，以及仍会留下的对手账户。每个受影响账户给出 accountId、name、balanceBefore、balanceAfter、change，全部是两位小数字符串。余额公式沿用 CurrentBalance.of：期初 + 收入 − 支出 + 转入 − 转出 + 调整。删除转账时整笔移除：对手账户若是转入侧，余额减少该金额；若是转出侧，余额增加该金额。没有跨账户转账时 affectedAccounts 为空，其他账户余额不变。被删账户本身不要列进仍存在账户的余额变化。
3. 预览给出 previewToken。它覆盖本次删除依据：目标账户 id 与 ARCHIVED、交易 id 集合、调整 id 集合、转账 id 及 from/to/amount、受影响账户的 balanceBefore。执行时在同一事务、锁账户之后重算；不一致则 409 PREVIEW_STALE，文案「删除影响已变化，请重新预览并确认」，不删除任何行。
4. 执行请求必须带上一次预览的 previewToken。同一本地事务内，按账户 id 升序锁定目标账户和全部转账对手账户，再删除该账户的交易、余额调整、相关整笔转账，最后删除账户。转账不能只删一侧。transaction_tags 随交易删除，不要留下可访问的业务残余。失败则整笔回滚。
5. 若 analysis_snapshots 或 analysis_generations 存在 account_id 等于该账户的行，预览仍可返回 analysisRecordCount，但执行必须 409 ANALYSIS_RECORDS_REMAIN，文案「该账户仍有分析记录，本次未删除任何数据」。不要删除、不要把 account_id 改成 NULL、不要改这些表。这是未获批的产品语义，停止在拒绝执行。
6. 不增加恢复接口、审计日志平台、批量删除或新的幂等子系统。已有 idempotency_records 无外键，不要为了删除去清全表。
7. 统计查询不要改写成新功能。源记录删除后，现有余额和统计自然不再算到它们。
8. 测试类必须命名为 AccountPermanentDeletionTest，放在 src/test/java，禁止 @SpringBootTest、禁止 DataSource、禁止 Flyway。用纯单元测试覆盖：未归档拒绝、对手账户余额前后与变化、整笔转账而非半笔、token 变化则拒绝且不调用删除、有分析记录则拒绝且不调用删除、锁 id 升序、跨用户不返回对方数据。可以另写不启动 Spring 的辅助类，但脚本只执行 AccountPermanentDeletionTest。

完成后用 Bootstrap JDK 只运行：
cd backend && ./mvnw -Dtest=AccountPermanentDeletionTest test
JAVA_HOME 是 ~/.local/share/bookkeeping-bootstrap/zulu25.36.205-ca-jdk25.0.4.1-macosx_aarch64/Contents/Home。
不要运行其他会连接数据库的测试。在最终说明写真实退出码，并写明集成测试 SKIPPED。不要 git commit。不要改 tools/s2-t08-handoff-once.sh。
PROMPT

echo "=== 2. Coding agent ==="
python3 - <<'PY'
import os, signal, subprocess
evidence = "/Users/limingyuan/Documents/code/personal/bookkeeping-s2-ab-lite/evidence/s2-t08-handoff"
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
if g status --porcelain | grep -q 'db/migration/'; then
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

echo "=== 4. Unit test ==="
cd "$ROOT/backend"
set +e
./mvnw -Dtest=AccountPermanentDeletionTest test > "$EVIDENCE/maven-test.log" 2>&1
TEST_RC=$?
set -e
printf 'maven_test=%s\nintegration=SKIPPED\n' "$TEST_RC" | tee "$EVIDENCE/backend-exit-codes.txt"
if grep -E -q 'Started BookkeepingApplication|Flyway|HikariPool' "$EVIDENCE/maven-test.log"; then
  printf 'STATUS: FAIL\n' | tee "$EVIDENCE/STATUS.txt"
  echo "unit test touched a database; supervisor not started"
  exit 2
fi
cd "$ROOT"
if [[ "$TEST_RC" != 0 ]]; then
  printf 'STATUS: FAIL\n' | tee "$EVIDENCE/STATUS.txt"
  echo "unit test failed; supervisor not started; no auto-fix"
  exit 2
fi

if [[ -n "$(g status --porcelain)" ]]; then
  g add -- backend tools/s2-t08-handoff-once.sh
  if g diff --cached --name-only | grep -q 'db/migration/'; then
    fail "refusing to commit a migration change"
  fi
  if g diff --cached --name-only | grep -q .; then
    g commit -m "$(cat <<'EOF'
S2-T08: preview and delete an archived account with its related records.

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
echo "commit=$COMMIT" | tee "$EVIDENCE/commit.txt"
g diff --no-ext-diff "$EXPECTED_HEAD" "$COMMIT" > "$EVIDENCE/commit.diff"
sha "$EVIDENCE/commit.diff" > "$EVIDENCE/commit.diff.sha256"
printf '%s\n' "$COMMIT" > "$EVIDENCE/commit.sha"

echo "=== 5. Snapshot and supervisor ==="
SNAP="$EVIDENCE/snapshot"
rm -rf "$SNAP"
mkdir -p "$SNAP/review-evidence" "$EVIDENCE/supervisor-log"
g archive --format=tar HEAD | tar -x -C "$SNAP"
cp "$EVIDENCE/commit.diff" "$EVIDENCE/commit.diff.sha256" "$EVIDENCE/commit.sha" "$EVIDENCE/backend-exit-codes.txt" "$SNAP/review-evidence/"
python3 - <<'PY'
import os, textwrap
evidence = "/Users/limingyuan/Documents/code/personal/bookkeeping-s2-ab-lite/evidence/s2-t08-handoff"
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
open("/tmp/s2-t08-supervisor.sb", "w").write(profile)
print("profile_written")
PY

cat > "$EVIDENCE/supervisor-prompt.txt" <<PROMPT
你是全新的 S2-T08 Supervisor。你不是刚才的 Coding Agent，禁止 resume。这是只读审核。

代码快照在 $SNAP。真实 diff 在快照内 review-evidence/commit.diff，其 SHA-256 在 review-evidence/commit.diff.sha256，完整提交 SHA 在 review-evidence/commit.sha。请先用 shasum 核对 diff 文件，并只依据这份 diff 与快照源码审核。父提交是 $EXPECTED_HEAD，本轮提交是 $COMMIT。测试日志在 $EVIDENCE/maven-test.log 与 backend-exit-codes.txt。

对照 AC-07～AC-10：仅归档账户可永久删除；预览含交易、调整、转账数量和受影响账户余额前后及变化；执行前校验预览依据，变化则拒绝并要求重新预览；同一事务删除账户、交易、调整和整笔转账；用户隔离；分析表行存在时不得删除或改写分析记录。确认没有新的 migration，没有 V16 改动，没有小程序永久删除页面。

不要修改任何文件。不要运行 MySQL、Flyway、npm、mvn。不要 commit、push、merge。请尝试把单词 wrote 写入 $SNAP/supervisor-wrote.txt；预期被执行环境拒绝。即使写入失败也继续完成审核。

最终结论必须包含这些独立行：
VERDICT: PASS
或 VERDICT: FAIL
或 VERDICT: BLOCKED
P0: <整数>
P1: <整数>
P2: <整数>
P3: <整数>
审核后停止。不要修改代码，不要启动 T09。
PROMPT

python3 - <<'PY'
import os, signal, subprocess
evidence = "/Users/limingyuan/Documents/code/personal/bookkeeping-s2-ab-lite/evidence/s2-t08-handoff"
snap = evidence + "/snapshot"
agent = os.path.expanduser("~/.local/bin/agent")
prompt = open(evidence + "/supervisor-prompt.txt", encoding="utf-8").read()
cmd = [
    "sandbox-exec", "-f", "/tmp/s2-t08-supervisor.sb",
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
