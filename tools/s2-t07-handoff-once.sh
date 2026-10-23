#!/bin/bash
# S2-T07 one-round Coding -> Supervisor handoff. max_rounds=1.
# Does not push, merge, clean, migrate, or start another task.
set -Eeuo pipefail

ROOT="/Users/limingyuan/Documents/code/personal/bookkeeping-s2-t00"
PRIMARY="/Users/limingyuan/Documents/code/personal/bookkeeping"
EVIDENCE="/Users/limingyuan/Documents/code/personal/bookkeeping-s2-ab-lite/evidence/s2-t07-handoff"
EXPECTED_HEAD="35ca6deea6121fbe9c3345a87d750f0239a1237b"
PLAN="docs/implementation/bookkeeping-v1.0-stage-s2-plan-v0.1-approved.md"
PLAN_SHA="69ff73a1c0f549cccd125d5b89271996076ab8685a4fe70745c91beb26561be5"
V16="backend/src/main/resources/db/migration/V16__create_transfers.sql"
V16_SHA="24c286fc5ef44f4581c0e21c35db4c54e02427f2b1d49f276a399e2ee81c9f9f"
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
[[ "$(g rev-parse HEAD)" == "$EXPECTED_HEAD" ]] || fail "HEAD is not the T06 baseline"
DIRTY="$(g status --porcelain)"
[[ -z "$DIRTY" || "$DIRTY" == "?? tools/s2-t07-handoff-once.sh" ]] || fail "worktree is not clean"
[[ "$(sha "$PLAN")" == "$PLAN_SHA" ]] || fail "plan SHA"
[[ "$(sha "$V16")" == "$V16_SHA" ]] || fail "V16 SHA"
AGENT="$(command -v agent || true)"
[[ -n "$AGENT" ]] || fail "agent CLI missing"
"$AGENT" status > "$EVIDENCE/agent-status.txt"
grep -q "Logged in" "$EVIDENCE/agent-status.txt" || fail "CLI is not logged in"
echo "identity PASS head=$EXPECTED_HEAD"

cat > "$EVIDENCE/coding-prompt.txt" <<'PROMPT'
你是本轮唯一的 S2-T07 Coding Agent。工作区是 /Users/limingyuan/Documents/code/personal/bookkeeping-s2-t00，分支 task/s2-t00-t01。只实现转账前端。不要提交，不要 push，不要启动另一个 Agent，不要进入 T08/T09/T10。

禁止：连接 MySQL；运行 Flyway 或 mvn；修改 V16__create_transfers.sql；修改正式计划文件；reset --hard；清理工作区；新增 tabBar；实现彻底删除账户；把金额用 JavaScript Number 当作权威金额。

先读这些文件再改代码：
- docs/product/bookkeeping-v1.0-product-baseline-v1.0.md 中 TRF-01～07
- docs/product/bookkeeping-v1.0-ui-freeze-v1.0.md 第 2.1～2.3 节和金额颜色
- frontend/miniprogram/miniprogram/ledger/ledger.ts
- frontend/miniprogram/miniprogram/ledger/post-save.ts
- frontend/miniprogram/miniprogram/pages/account-home/index.ts 与 index.wxml
- frontend/miniprogram/miniprogram/transaction/submission.ts
- frontend/miniprogram/miniprogram/transaction/idempotency.ts
- frontend/miniprogram/miniprogram/services/transaction.ts
- frontend/miniprogram/miniprogram/account/archive-copy.ts
- frontend/miniprogram/miniprogram/app.json
- backend TransferController 与 LedgerService 里 TRANSFER 分支（只读，确认契约）

T06 契约（Mock 即可，不要声称 MySQL 已通过）：
- POST /api/v1/transfers，请求头 Idempotency-Key。正文 fromAccountId、toAccountId、amount、fromBusinessTime、toBusinessTime。新建 201；同一键同一正文重放 200，响应头 Idempotent-Replay: true。
- GET /api/v1/transfers/{id}，PUT 同一正文且无幂等键，DELETE 204。
- 响应字段：id、fromAccountId、toAccountId、amount（正数小数字符串）、fromBusinessTime、toBusinessTime、firstSaveOrder。不发送 firstSavedAt。
- 流水 GET /api/v1/accounts/{accountId}/ledger?month=YYYY-MM 增加 kind=TRANSFER，displayName=转账，金额为带符号的两位小数字符串：转出账户为负，转入账户为正。businessTime 是这一侧的时间。transactionType、category、tags 为 null 时不出现在 JSON。月份列表是服务端一次返回的完整有序列表，不是游标分页。排序已经由服务端完成，前端不得按 kind 再排序。现有 ‹ › 月份切换就是分页。
- 错误：400 VALIDATION_FAILED，同账户明细 toAccountId/MUST_DIFFER，非正金额明细 amount/MUST_BE_POSITIVE；404 RESOURCE_NOT_FOUND；409 ACCOUNT_ARCHIVED；409 IDEMPOTENCY_CONFLICT。

实现要求：
1. parseLedger 接受 TRANSFER，且允许省略 transactionType、category、tags。TRANSACTION 与 BALANCE_ADJUSTMENT 的现有解析不要放宽。
2. LedgerRow.kind 增加 "transfer"。行标题用 displayName，typeLabel 为「转账」，金额标签和 moneyToneClass 按符号：正数 money-positive，负数 money-negative，零 money-zero。保留服务端顺序。
3. 账户主页在未归档时显示「转账」，与「记一笔」「余额调整」并列。归档账户不显示「转账」，仍可点开历史里的转账行。
4. 新增创建页和详情页，并注册到 app.json，不要添加 tabBar。创建页从当前账户进入。两个账户都只能选 ACTIVE 账户。金额必须是大于 0 的十进制字符串，最多两位小数，提交前拒绝 0、负数和两个相同账户。
5. 两侧业务时间默认相同，且默认为当前北京时间，格式 yyyy-MM-ddTHH:mm:ss+08:00，秒精度。同步开启时，改任一侧会同时改另一侧。提供明确的解除同步和恢复同步。恢复同步时把转出时间写回转入时间并重新打开同步。允许过去、现在、未来，不要求转入时间晚于转出时间。
6. 创建使用一次幂等键。不确定失败（网络、超时、无法识别的响应）重试必须复用同一键和同一正文。明确的校验失败后，下一次新的保存可以换新键。更新和删除不发送 Idempotency-Key。
7. 保存成功后回到当前来源账户的流水。来源账户仍是转出或转入账户时，月份用来源这一侧的业务时间。来源账户已不再参与这笔转账时，进入转入账户对应月份。定位 kind=TRANSFER 的记录。找不到时提示「转账已保存，但暂未定位到记录」。
8. 详情页可修改金额、两个账户和两个时间，id 保持为路径中的转账。删除前确认「删除整笔转账？」，成功后回到来源账户流水。归档账户或任一关联账户已归档时，详情只读，不提供保存和删除，并显示「账户已归档，恢复后才能修改关联业务」。
9. 错误文案：同账户「请选择两个不同的账户」；非正金额「请输入大于 0 的金额」；ACCOUNT_ARCHIVED 用上面的归档句子；IDEMPOTENCY_CONFLICT 沿用交易保存里的冲突口径；找不到「转账不存在或已删除」。页面要刷新相关流水状态。
10. 不要把转账算进收入、支出或结余统计，服务端 summary 仍只含交易。搜索仍是交易名称搜索，不要新做转账搜索。
11. 为解析、正负金额颜色、服务端顺序、幂等键复用、同步解除与恢复、归档只读、同账户和非正金额的提交前拒绝补测试。现有测试保持通过。

完成后在 frontend/miniprogram 运行 npm test、npm run typecheck、npm run lint。PATH 需包含 ~/.nvm/versions/node/v22.23.3/bin。若 node_modules 不存在，先 npm ci。把命令的真实退出码写进你的最终说明。不要 git commit。不要改 tools/s2-t07-handoff-once.sh。
PROMPT

echo "=== 2. Coding agent ==="
python3 - <<'PY'
import os, signal, subprocess
evidence = os.environ["EVIDENCE"] if False else "/Users/limingyuan/Documents/code/personal/bookkeeping-s2-ab-lite/evidence/s2-t07-handoff"
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
  g add -- frontend tools/s2-t07-handoff-once.sh
  if [[ -f docs/implementation/s2-t07-t06-defect.md ]]; then
    g add -- docs/implementation/s2-t07-t06-defect.md
  fi
  if g diff --cached --name-only | grep -q .; then
    g commit -m "$(cat <<'EOF'
S2-T07: show a transfer on both account ledgers and edit the whole record.

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

echo "=== 5. Snapshot and supervisor ==="
SNAP="$EVIDENCE/snapshot"
rm -rf "$SNAP"
mkdir -p "$SNAP" "$EVIDENCE/supervisor-log"
g archive --format=tar HEAD | tar -x -C "$SNAP"
python3 - <<'PY'
import os, textwrap
evidence = "/Users/limingyuan/Documents/code/personal/bookkeeping-s2-ab-lite/evidence/s2-t07-handoff"
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
open("/tmp/s2-t07-supervisor.sb", "w").write(profile)
print("profile_written")
PY

cat > "$EVIDENCE/supervisor-prompt.txt" <<PROMPT
你是全新的 S2-T07 Supervisor。你不是刚才的 Coding Agent，禁止 resume 或继续任何编码会话。这是只读审核。

代码快照在 $SNAP。正式产品与 UI 基线在快照的 docs/product/bookkeeping-v1.0-product-baseline-v1.0.md 和 docs/product/bookkeeping-v1.0-ui-freeze-v1.0.md。本轮提交是 $COMMIT，父提交是 $EXPECTED_HEAD。测试日志在 $EVIDENCE/npm-test.log、npm-typecheck.log、npm-lint.log 和 frontend-exit-codes.txt。编码输出在 $EVIDENCE/coding-agent.txt。

请核对 TRF-01～07 和 UI Freeze：转账创建、查询、编辑、整笔删除；两个不同正常账户和正数金额；两侧时间默认同步、可解除、可恢复；保存后来源账户或转入账户流水；统一流水按 kind 进入交易、转账或余额调整；转出为负、转入为正并按各自时间进月；正红负绿零中性；归档只读；创建幂等键在不确定重试时复用；混合 kind 保持服务端顺序，月份切换是分页。

不要修改任何文件。不要运行 MySQL、Flyway、npm、mvn。不要 commit、push、merge。请尝试把单词 wrote 写入 $SNAP/supervisor-wrote.txt；预期被执行环境拒绝。即使写入失败也继续完成审核。

最终结论必须包含这些独立行：
VERDICT: PASS
或 VERDICT: FAIL
或 VERDICT: BLOCKED
P0: <整数>
P1: <整数>
P2: <整数>
P3: <整数>
审核后停止。不要提出由你来修改代码，不要启动下一 Task。
PROMPT

python3 - <<'PY'
import os, signal, subprocess
evidence = "/Users/limingyuan/Documents/code/personal/bookkeeping-s2-ab-lite/evidence/s2-t07-handoff"
snap = evidence + "/snapshot"
agent = os.path.expanduser("~/.local/bin/agent")
prompt = open(evidence + "/supervisor-prompt.txt", encoding="utf-8").read()
cmd = [
    "sandbox-exec", "-f", "/tmp/s2-t07-supervisor.sb",
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
