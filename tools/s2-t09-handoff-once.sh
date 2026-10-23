#!/bin/bash
# S2-T09 one-round Coding -> Supervisor handoff. max_rounds=1.
# Reuses the T08 seatbelt and snapshot pattern. Does not push, migrate, or start T10.
set -Eeuo pipefail

ROOT="/Users/limingyuan/Documents/code/personal/bookkeeping-s2-t00"
PRIMARY="/Users/limingyuan/Documents/code/personal/bookkeeping"
EVIDENCE="/Users/limingyuan/Documents/code/personal/bookkeeping-s2-ab-lite/evidence/s2-t09-handoff"
EXPECTED_HEAD="25b16885e1574676ffbb0981781d10308fc6d8ba"
PLAN="docs/implementation/bookkeeping-v1.0-stage-s2-plan-v0.1-approved.md"
PLAN_SHA="69ff73a1c0f549cccd125d5b89271996076ab8685a4fe70745c91beb26561be5"
V16="backend/src/main/resources/db/migration/V16__create_transfers.sql"
V16_SHA="24c286fc5ef44f4581c0e21c35db4c54e02427f2b1d49f276a399e2ee81c9f9f"
ADJ="docs/implementation/s2-t08-t09-adj-001.md"
ADJ_SHA="f6e51b3ed178f1703d300cb7c249c8b93ebddb00815b53a8352fab10c4154c3d"
BRANCH="task/s2-t00-t01"
NODE_BIN="$HOME/.nvm/versions/node/v22.23.3/bin"
export PATH="$HOME/.local/bin:$NODE_BIN:/usr/bin:/bin:/usr/sbin:/sbin:/usr/local/bin:/opt/homebrew/bin:$PATH"

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
[[ "$(g rev-parse HEAD)" == "$EXPECTED_HEAD" ]] || fail "HEAD is not the T09 backend baseline"
DIRTY="$(g status --porcelain)"
[[ -z "$DIRTY" || "$DIRTY" == "?? tools/s2-t09-handoff-once.sh" ]] || fail "worktree is not clean"
[[ "$(sha "$PLAN")" == "$PLAN_SHA" ]] || fail "plan SHA"
[[ "$(sha "$V16")" == "$V16_SHA" ]] || fail "V16 SHA"
AGENT="$(command -v agent || true)"
[[ -n "$AGENT" ]] || fail "agent CLI missing"
"$AGENT" status > "$EVIDENCE/agent-status.txt"
grep -q "Logged in" "$EVIDENCE/agent-status.txt" || fail "CLI is not logged in"
python3 - <<'PY'
from pathlib import Path
text = """S2-T08/T09-ADJ-001
日期: 2026-10-23
状态: 组长内部任务调整记录。未改获批计划原件。未改业务源码。

授权依据:
- 获批计划 docs/implementation/bookkeeping-v1.0-stage-s2-plan-v0.1-approved.md SHA-256 69ff73a1c0f549cccd125d5b89271996076ab8685a4fe70745c91beb26561be5，第 8.4 条：里程碑内普通任务调整由组长记录版本、Diff、SHA 与原因，不能替换原始批准原件。
- 治理 v1.7 文件 SHA-256 4fcf410092429fa97aa21baaddc91b027fa0d006cee08af59e46b49bac4c56c1，§4.8：组长可拆合里程碑内任务，须标注初版 SHA，不得削减功能或 Quality Gate，不得把调查完成当成编码批准。分析快照删除语义仍属 APPROVE / 计划 DB-03。
- 组长 2026-10-23 裁断：接受 25b16885e1574676ffbb0981781d10308fc6d8ba 为 T09 后端阶段性成果；T08 只读审查不取消。

相对获批计划的 Diff:
- T08 原文：只读设计加必要审批。交付预览契约、转账两侧、并发重验、V14 处置、回滚、锁与事务风险。不得把历史报告删除、保留或失效当成已批准。
- T08 调整后：仍是只读审查，不拥有 25b1688 的代码。本记录所附调查是 T08 未覆盖风险的交付，不新开编码。
- T09 原文：依赖 T08 设计完成之后，编码包含永久删除后端和小程序。
- T09 调整后：后端阶段性成果定为 25b16885e1574676ffbb0981781d10308fc6d8ba，父提交 41d98a3dfc7d06eb51be860c120ab93b55bd2e5e。小程序尚未开始。分析记录的删除、置空或失效不在已接受代码内，也不因本次调整而变为可编码。
- 未改 M1/M2 边界、T10/T11 Gate、AC-07～AC-10、AI-01、AI-07。未批准 DB-03。

证据:
- 后端单测 AccountPermanentDeletionTest 7 项，退出码 0。集成测试 SKIPPED。独立 Supervisor 对该提交 PASS，P0=0，P1=0，P2=0，P3=1。
"""
path = Path("/Users/limingyuan/Documents/code/personal/bookkeeping-s2-t00/docs/implementation/s2-t08-t09-adj-001.md")
path.write_text(text, encoding="utf-8")
print("adj_written", path.stat().st_size)
PY
[[ "$(sha "$ADJ")" == "$ADJ_SHA" ]] || fail "adjustment record SHA"
echo "identity PASS head=$EXPECTED_HEAD"

cat > "$EVIDENCE/coding-prompt.txt" <<'PROMPT'
你是本轮唯一的 S2-T09 Coding Agent。工作区是 /Users/limingyuan/Documents/code/personal/bookkeeping-s2-t00，分支 task/s2-t00-t01。只实现归档账户永久删除的小程序交互。不要提交，不要 push，不要启动另一个 Agent，不要进入 T10/T11。

禁止：修改 backend、db/migration、V14、V16、正式计划 bookkeeping-v1.0-stage-s2-plan-v0.1-approved.md、docs/implementation/s2-t08-t09-adj-001.md；连接 MySQL；运行 Flyway 或 mvn；删除、置空或失效 AI 报告；新增 tabBar；reset --hard。

先读：
- docs/product/bookkeeping-v1.0-product-baseline-v1.0.md 的 AC-07～AC-10
- docs/product/bookkeeping-v1.0-ui-freeze-v1.0.md 第 2.3 节和金额颜色
- frontend 的 pages/account-settings、pages/account-archive、account/archive-copy.ts、account/money-tone.ts、services/account.ts、app.json
- 后端 AccountController 的永久删除接口，只读，不要改

已固定的后端契约：
- POST /api/v1/accounts/{accountId}/permanent-deletion/preview，无正文。响应 transactionCount、adjustmentCount、transferCount、analysisRecordCount、affectedAccounts、previewToken。affectedAccounts 每项有 accountId、name、balanceBefore、balanceAfter、change，都是两位小数字符串。
- POST /api/v1/accounts/{accountId}/permanent-deletion，正文只有 previewToken。成功 204。
- 409 ACCOUNT_NOT_ARCHIVED：只有已归档账户可以永久删除。
- 409 PREVIEW_STALE：删除影响已变化，请重新预览并确认。
- 409 ANALYSIS_RECORDS_REMAIN：该账户仍有分析记录，本次未删除任何数据。
- 404 RESOURCE_NOT_FOUND。不要把这些响应当成删除成功。

实现：
1. 只有归档账户能进入。账户设置在 lifecycle 为 ARCHIVED 时显示「永久删除」。ACTIVE 不显示。正常账户主页不提供入口。
2. 新页面先请求预览。展示普通交易数、余额调整数、关联转账数。对每个仍存在的对手账户展示名称、删除前、删除后、变化，金额颜色用现有 moneyToneClass：正红、负绿、零中性。直接展示服务端字符串，不要用 JavaScript Number 重算权威金额。没有对手账户时说明其他账户余额不变。
3. 明确写出永久删除后不可恢复，账户及其交易、余额调整和关联转账会一并删除。用户必须再确认。确认请求只提交本次预览的 previewToken。
4. analysisRecordCount 大于 0 时，展示 ANALYSIS_RECORDS_REMAIN 的文案并禁止确认。不要调用任何分析接口。若执行仍返回该错误，页面保持未删除，不得跳到成功态。
5. PREVIEW_STALE 时展示文案，丢弃旧 token，重新预览，不得显示成功。ACCOUNT_NOT_ARCHIVED 与 404 同样不得显示成功。
6. 204 之后离开已删账户的设置和主页，回到归档列表并刷新。正常账户列表和归档列表都不要继续显示该账户。
7. 在 app.json 注册新页面，不要加 tabBar。补测试：预览解析、金额颜色、token 随执行提交、三类错误不是成功、有分析记录时不能确认、非归档不能进入。现有测试保持通过。

完成后在 frontend/miniprogram 运行 npm test、npm run typecheck、npm run lint。PATH 含 ~/.nvm/versions/node/v22.23.3/bin。node_modules 不存在时先 npm ci。最终说明写真实退出码。不要 git commit。不要改 tools/s2-t09-handoff-once.sh。
PROMPT

echo "=== 2. Coding agent ==="
python3 - <<'PY'
import os, signal, subprocess
evidence = "/Users/limingyuan/Documents/code/personal/bookkeeping-s2-ab-lite/evidence/s2-t09-handoff"
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
if g status --porcelain | grep -E -q 'backend/|db/migration/'; then
  fail "backend or migration changed"
fi
HEAD_NOW="$(g rev-parse HEAD)"
echo "head_after_coding=$HEAD_NOW"
g status --porcelain > "$EVIDENCE/status-after-coding.txt" || true
if [[ "$HEAD_NOW" != "$EXPECTED_HEAD" ]]; then
  PARENT="$(g rev-parse "$HEAD_NOW^")"
  [[ "$PARENT" == "$EXPECTED_HEAD" ]] || fail "coding agent rewrote history"
fi
if ! g status --porcelain | grep -q 'frontend/'; then
  printf 'STATUS: BLOCKED\n' | tee "$EVIDENCE/STATUS.txt"
  echo "coding agent made no frontend change; supervisor not started"
  exit 1
fi

echo "=== 4. Frontend checks ==="
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
printf 'npm_test=%s\nnpm_typecheck=%s\nnpm_lint=%s\n' "$TEST_RC" "$TYPE_RC" "$LINT_RC" | tee "$EVIDENCE/frontend-exit-codes.txt"
cd "$ROOT"
if [[ "$TEST_RC" != 0 || "$TYPE_RC" != 0 || "$LINT_RC" != 0 ]]; then
  printf 'STATUS: FAIL\n' | tee "$EVIDENCE/STATUS.txt"
  echo "frontend checks failed; supervisor not started; no auto-fix"
  exit 2
fi

if [[ -n "$(g status --porcelain)" ]]; then
  g add -- frontend tools/s2-t09-handoff-once.sh "$ADJ"
  if g diff --cached --name-only | grep -E -q 'backend/|db/migration/|bookkeeping-v1.0-stage-s2-plan'; then
    fail "refusing to commit backend, migration, or plan"
  fi
  if g diff --cached --name-only | grep -q .; then
    g commit -m "$(cat <<'EOF'
S2-T09: confirm permanent deletion from an archived account.

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
[[ "$(sha "$ADJ")" == "$ADJ_SHA" ]] || fail "adjustment record SHA after commit"
echo "commit=$COMMIT" | tee "$EVIDENCE/commit.txt"
g diff --no-ext-diff "$EXPECTED_HEAD" "$COMMIT" > "$EVIDENCE/commit.diff"
sha "$EVIDENCE/commit.diff" > "$EVIDENCE/commit.diff.sha256"
printf '%s\n' "$COMMIT" > "$EVIDENCE/commit.sha"

echo "=== 5. Snapshot and supervisor ==="
SNAP="$EVIDENCE/snapshot"
rm -rf "$SNAP"
mkdir -p "$SNAP/review-evidence" "$EVIDENCE/supervisor-log"
g archive --format=tar HEAD | tar -x -C "$SNAP"
cp "$EVIDENCE/commit.diff" "$EVIDENCE/commit.diff.sha256" "$EVIDENCE/commit.sha" "$EVIDENCE/frontend-exit-codes.txt" "$SNAP/review-evidence/"
python3 - <<'PY'
import os, textwrap
evidence = "/Users/limingyuan/Documents/code/personal/bookkeeping-s2-ab-lite/evidence/s2-t09-handoff"
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
open("/tmp/s2-t09-supervisor.sb", "w").write(profile)
print("profile_written")
PY

cat > "$EVIDENCE/supervisor-prompt.txt" <<PROMPT
你是全新的 S2-T09 Supervisor。你不是刚才的 Coding Agent，禁止 resume。这是只读审核。

代码快照在 $SNAP。真实 diff 在快照内 review-evidence/commit.diff，SHA-256 在 review-evidence/commit.diff.sha256，完整提交在 review-evidence/commit.sha。请先 shasum 核对 diff。父提交是 $EXPECTED_HEAD，本轮提交是 $COMMIT。前端日志在 $EVIDENCE/npm-test.log、npm-typecheck.log、npm-lint.log 和 frontend-exit-codes.txt。

对照 AC-07～AC-10 和 UI Freeze 2.3：仅归档账户可进入永久删除；预览展示交易、余额调整、转账数量和对手账户余额前后及变化；不可恢复提示和确认；执行提交 previewToken；PREVIEW_STALE、ACCOUNT_NOT_ARCHIVED、ANALYSIS_RECORDS_REMAIN 不得显示为成功；成功后归档列表不再显示该账户。分析记录只提示，不删除、不置空、不失效。确认计划原件和 V16 未改，调整记录 docs/implementation/s2-t08-t09-adj-001.md 的 SHA-256 仍是 $ADJ_SHA。确认没有后端删除逻辑改动。

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
evidence = "/Users/limingyuan/Documents/code/personal/bookkeeping-s2-ab-lite/evidence/s2-t09-handoff"
snap = evidence + "/snapshot"
agent = os.path.expanduser("~/.local/bin/agent")
prompt = open(evidence + "/supervisor-prompt.txt", encoding="utf-8").read()
cmd = [
    "sandbox-exec", "-f", "/tmp/s2-t09-supervisor.sb",
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
