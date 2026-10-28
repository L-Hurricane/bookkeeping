/**
 * Bounded T08 supplement against bk_s6_family_e2e_20261027 only.
 * Expected category amounts come from the events file plus each user's own tree.
 */
import { readFileSync } from "node:fs";

const SCHEMA = "bk_s6_family_e2e_20261027";
const base = "http://127.0.0.1:18081";
const envPath = process.argv[2];
const factsPath = process.argv[3];
if (!envPath || !factsPath) process.exit(2);
const env = Object.fromEntries(readFileSync(envPath, "utf8").split("\n").filter((line) => line.includes("=")).map((line) => {
  const index = line.indexOf("=");
  return [line.slice(0, index), line.slice(index + 1)];
}));
if (!String(env.S6_FAMILY_DB_URL).includes(`/${SCHEMA}`)) {
  process.stderr.write("refusing a database url that is not the approved family schema\n");
  process.exit(2);
}
const events = readFileSync(factsPath, "utf8").trim().split("\n").map((line) => JSON.parse(line));
const findings = [];
function note(name, ok, detail) {
  findings.push({ name, ok, detail });
  process.stdout.write(`${ok ? "PASS" : "FAIL"} ${name} ${detail}\n`);
}

async function call(token, method, path, body, key) {
  const headers = {};
  if (body !== undefined) headers["content-type"] = "application/json";
  if (token) headers.authorization = `Bearer ${token}`;
  if (key) headers["idempotency-key"] = key;
  const response = await fetch(`${base}${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await response.text();
  let payload = null;
  if (text.length > 0) {
    try { payload = JSON.parse(text); } catch { payload = text.slice(0, 180); }
  }
  return { status: response.status, payload };
}
async function ok(token, method, path, body, key) {
  const result = await call(token, method, path, body, key);
  if (result.status >= 300) {
    process.stderr.write(`${method} ${path} ${result.status}\n`);
    process.exit(1);
  }
  return result.payload;
}
async function login(username, password) {
  const result = await ok(null, "POST", "/api/v1/auth/login", { username, password });
  return result.token;
}
function money(cents) {
  const sign = cents < 0 ? "-" : "";
  const abs = Math.abs(cents);
  return `${sign}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, "0")}`;
}
function share(amountCents, netCents) {
  if (netCents === 0) return null;
  const absNet = Math.abs(netCents);
  const num = amountCents * 10000;
  const sign = num < 0 ? -1 : 1;
  const rounded = Math.floor((Math.abs(num) + Math.floor(absNet / 2)) / absNet);
  const scaled = sign * rounded;
  const frac = Math.abs(scaled % 100);
  return `${scaled < 0 ? "-" : ""}${Math.abs(Math.trunc(scaled / 100))}.${String(frac).padStart(2, "0")}`;
}
function flatten(nodes, parentName, into) {
  for (const node of nodes) {
    into.push({ id: node.id, name: node.name, parentName: parentName ?? null, children: node.children ?? [] });
    flatten(node.children ?? [], node.name, into);
  }
}
function subtreeNames(node) {
  const names = [node.name];
  for (const child of node.children ?? []) names.push(...subtreeNames(child));
  return names;
}
function findNode(nodes, name) {
  for (const node of nodes) {
    if (node.name === name) return node;
    const found = findNode(node.children ?? [], name);
    if (found) return found;
  }
  return null;
}
function sumNames(kind, names, from, to) {
  let cents = 0;
  let count = 0;
  for (const event of events) {
    if (event.kind !== kind) continue;
    const day = event.businessTime.slice(0, 10);
    if (from && (day < from || day > to)) continue;
    const category = event.category ?? null;
    if (names === null) {
      if (category !== null) continue;
    } else if (!names.includes(category)) continue;
    cents += event.kind === "EXPENSE" || event.kind === "INCOME" ? event.amountCents : 0;
    count += 1;
  }
  return { cents, count };
}

const owner = await login(env.S6_FAMILY_OWNER_USERNAME, env.S6_FAMILY_OWNER_PASSWORD);
const auto = await login(env.S6_FAMILY_AUTO_USERNAME, env.S6_FAMILY_AUTO_PASSWORD);
const ownerBefore = await ok(owner, "GET", "/api/v1/accounts");
const autoBefore = await ok(auto, "GET", "/api/v1/accounts");

async function checkTree(token, label) {
  const categories = await ok(token, "GET", "/api/v1/categories?type=EXPENSE");
  const report = await ok(token, "GET", "/api/v1/statistics?preset=CUSTOM&from=2025-11-01&to=2026-10-27&accountScope=ALL");
  const flat = [];
  flatten(categories.items, null, flat);
  const depth = Math.max(...flat.map((node) => node.parentName === null ? 1 : 2));
  let mismatches = 0;
  function walk(nodes) {
    for (const node of nodes) {
      const expected = sumNames("EXPENSE", subtreeNames(node), "2025-11-01", "2026-10-27");
      if (node.transactionCount !== expected.count || node.amount !== money(expected.cents)) mismatches += 1;
      const childCents = (node.children ?? []).reduce((total, child) => total + Number(child.amount.replace(".", "")), 0);
      const own = expected.cents;
      if ((node.children ?? []).length > 0 && childCents > own) mismatches += 1;
      walk(node.children ?? []);
    }
  }
  walk(report.expense.categories);
  const uncategorized = sumNames("EXPENSE", null, "2025-11-01", "2026-10-27");
  const slice = report.expense.uncategorized;
  const sliceOk = slice.transactionCount === uncategorized.count && slice.amount === money(uncategorized.cents);
  note(`${label} expense tree`, mismatches === 0 && sliceOk, `nodes_mismatch=${mismatches} uncategorized=${slice.amount}/${slice.transactionCount} depth_seen=${depth}`);
  const food = findNode(report.expense.categories, "餐饮");
  if (food) {
    const drill = await ok(token, "GET", `/api/v1/statistics/transactions?preset=CUSTOM&from=2026-09-01&to=2026-09-30&accountScope=ALL&type=EXPENSE&categoryId=${food.id}`);
    const september = await ok(token, "GET", "/api/v1/statistics?preset=CUSTOM&from=2026-09-01&to=2026-09-30&accountScope=ALL");
    const septemberFood = findNode(september.expense.categories, "餐饮");
    note(`${label} category drill`, drill.items.length === septemberFood.transactionCount, `drill=${drill.items.length} node=${septemberFood.transactionCount}`);
  }
  const bare = await ok(token, "GET", "/api/v1/statistics/transactions?preset=CUSTOM&from=2026-10-28&to=2026-10-31&accountScope=ACTIVE&type=EXPENSE&uncategorized=true");
  note(`${label} empty uncategorized drill`, bare.items.length === 0, `rows=${bare.items.length}`);
  return { flat, report };
}

const ownerTree = await checkTree(owner, "owner");
const autoTree = await checkTree(auto, "auto");
const ownerLife = ownerTree.flat.some((node) => node.name === "生活" && node.parentName === null);
const autoMarket = autoTree.flat.find((node) => node.name === "超市");
const autoLife = autoTree.flat.find((node) => node.name === "生活");
note("dataset trees differ", ownerLife && autoMarket && autoMarket.parentName === null && !autoLife, `owner_life=${ownerLife} auto_market_parent=${autoMarket ? autoMarket.parentName : "missing"}`);

const started = Date.now();
let cursor = null;
const keys = new Set();
let pages = 0;
let rows = 0;
let transferSides = new Map();
do {
  const path = `/api/v1/ledger?accountScope=ALL${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`;
  const page = await ok(auto, "GET", path);
  pages += 1;
  for (const item of page.items) {
    const key = `${item.kind}:${item.id}:${item.accountId}`;
    if (keys.has(key)) note("page duplicate", false, key);
    keys.add(key);
    rows += 1;
    if (item.kind === "TRANSFER") {
      const side = transferSides.get(item.id) ?? [];
      side.push(item);
      transferSides.set(item.id, side);
    }
  }
  cursor = page.nextCursor;
  if (pages > 400) process.exit(1);
} while (cursor);
const pageMs = Date.now() - started;
let transferOk = transferSides.size === 2;
for (const sides of transferSides.values()) {
  if (sides.length !== 2 || sides[0].businessTime === sides[1].businessTime) transferOk = false;
}
note("auto full cursor", transferOk && rows === keys.size, `pages=${pages} rows=${rows} transfers=${transferSides.size} ms=${pageMs}`);

const createdA = await ok(auto, "POST", "/api/v1/accounts", { name: "补测甲", kind: "NORMAL", openingBalance: "100.00" });
const createdB = await ok(auto, "POST", "/api/v1/accounts", { name: "补测乙", kind: "NORMAL", openingBalance: "50.00" });
const replayBody = { type: "EXPENSE", amount: "1.00", name: "重复提交", categoryId: null, tagIds: [], businessTime: "2024-02-02T10:00:00+08:00", remark: "" };
const first = await call(auto, "POST", `/api/v1/accounts/${createdA.id}/transactions`, replayBody, "t08-replay-1");
const second = await call(auto, "POST", `/api/v1/accounts/${createdA.id}/transactions`, replayBody, "t08-replay-1");
note("idempotent replay", first.status === 201 && second.status === 200 && first.payload.id === second.payload.id, `status=${first.status}/${second.status}`);
const parallelBody = (amount) => ({ type: "EXPENSE", amount, name: "并发", categoryId: null, tagIds: [], businessTime: "2024-02-02T11:00:00+08:00", remark: "" });
const [left, right] = await Promise.all([
  call(auto, "POST", `/api/v1/accounts/${createdA.id}/transactions`, parallelBody("2.00"), "t08-same-1"),
  call(auto, "POST", `/api/v1/accounts/${createdA.id}/transactions`, parallelBody("2.00"), "t08-same-1"),
]);
const sameId = left.payload?.id && left.payload.id === right.payload?.id;
note("concurrent same key", sameId && [left.status, right.status].sort().join("/") === "200/201", `status=${left.status}/${right.status}`);
const other = await Promise.all([
  call(auto, "POST", `/api/v1/accounts/${createdA.id}/transactions`, parallelBody("3.00"), "t08-diff-1"),
  call(auto, "POST", `/api/v1/accounts/${createdA.id}/transactions`, parallelBody("4.00"), "t08-diff-2"),
]);
note("concurrent different keys", other.every((item) => item.status === 201) && other[0].payload.id !== other[1].payload.id, `status=${other.map((item) => item.status).join("/")}`);

const root = await ok(auto, "POST", "/api/v1/categories", { type: "EXPENSE", name: "补测层", parentId: null });
const middle = await ok(auto, "POST", "/api/v1/categories", { type: "EXPENSE", name: "补测支", parentId: root.id });
const leaf = await ok(auto, "POST", "/api/v1/categories", { type: "EXPENSE", name: "补测叶", parentId: middle.id });
const negative = await ok(auto, "POST", "/api/v1/categories", { type: "EXPENSE", name: "补测负", parentId: null });
const samples = [["10.00", leaf.id, "正一"], ["20.00", leaf.id, "正二"], ["-5.00", leaf.id, "负叶"], ["-1.00", negative.id, "负根"], ["-3.00", null, "未分类负"]];
let sequence = 0;
for (const [amount, categoryId, name] of samples) {
  sequence += 1;
  await ok(auto, "POST", `/api/v1/accounts/${createdA.id}/transactions`, {
    type: "EXPENSE", amount, name, categoryId, tagIds: [], businessTime: `2024-01-15T12:00:0${sequence}+08:00`, remark: "",
  }, `t08-cat-${sequence}`);
}
const isolated = await ok(auto, "GET", `/api/v1/statistics?preset=CUSTOM&from=2024-01-01&to=2024-01-31&accountScope=SELECTED&accountIds=${createdA.id}`);
const leafNode = findNode(isolated.expense.categories, "补测叶");
const branchNode = findNode(isolated.expense.categories, "补测支");
const rootNode = findNode(isolated.expense.categories, "补测层");
const negativeNode = findNode(isolated.expense.categories, "补测负");
const expectedLeaf = "25.00";
const treeOk = leafNode && branchNode && rootNode && negativeNode
  && leafNode.amount === expectedLeaf && branchNode.amount === expectedLeaf && rootNode.amount === expectedLeaf
  && leafNode.transactionCount === 3 && negativeNode.amount === "-1.00"
  && isolated.expense.uncategorized.amount === "-3.00" && isolated.expense.uncategorized.transactionCount === 1
  && isolated.expense.net === "21.00";
note("three level and negative", Boolean(treeOk), `net=${isolated.expense.net} leaf=${leafNode && leafNode.amount} negative=${negativeNode && negativeNode.amount} uncategorized=${isolated.expense.uncategorized.amount}`);
const netCents = 2100;
note("negative share", rootNode && rootNode.share === share(2500, netCents) && negativeNode.share === share(-100, netCents) && isolated.expense.uncategorized.share === share(-300, netCents), `root=${rootNode && rootNode.share} negative=${negativeNode && negativeNode.share} uncategorized=${isolated.expense.uncategorized.share}`);
const leafDrill = await ok(auto, "GET", `/api/v1/statistics/transactions?preset=CUSTOM&from=2024-01-01&to=2024-01-31&accountScope=SELECTED&accountIds=${createdA.id}&type=EXPENSE&categoryId=${root.id}`);
const bareDrill = await ok(auto, "GET", `/api/v1/statistics/transactions?preset=CUSTOM&from=2024-01-01&to=2024-01-31&accountScope=SELECTED&accountIds=${createdA.id}&type=EXPENSE&uncategorized=true`);
note("three level drill", leafDrill.items.length === 3 && bareDrill.items.length === 1, `parent_rows=${leafDrill.items.length} uncategorized_rows=${bareDrill.items.length}`);

const life = await ok(auto, "POST", "/api/v1/categories", { type: "EXPENSE", name: "生活", parentId: null });
const movedMarket = await call(auto, "POST", `/api/v1/categories/${autoMarket.id}/move`, { parentId: life.id });
const favor = autoTree.flat.find((node) => node.name === "人情");
const movedFavor = await call(auto, "POST", `/api/v1/categories/${favor.id}/move`, { parentId: life.id });
const after = await ok(auto, "GET", "/api/v1/statistics?preset=CUSTOM&from=2025-11-01&to=2026-10-27&accountScope=ALL");
const lifeNode = findNode(after.expense.categories, "生活");
const expectedLife = sumNames("EXPENSE", ["超市", "人情"], "2025-11-01", "2026-10-27");
note("auto parent move", movedMarket.status < 300 && movedFavor.status < 300 && lifeNode && lifeNode.amount === money(expectedLife.cents) && lifeNode.transactionCount === expectedLife.count, `move=${movedMarket.status}/${movedFavor.status} life=${lifeNode && lifeNode.amount} expected=${money(expectedLife.cents)} count=${lifeNode && lifeNode.transactionCount}/${expectedLife.count}`);

await ok(auto, "POST", "/api/v1/transfers", {
  fromAccountId: createdA.id, toAccountId: createdB.id, amount: "10.00",
  fromBusinessTime: "2024-03-31T22:00:00+08:00", toBusinessTime: "2024-04-01T08:00:00+08:00",
}, "t08-transfer-1");
await ok(auto, "POST", `/api/v1/accounts/${createdA.id}/balance-adjustments`, {
  targetBalance: "80.00", businessTime: "2024-05-06T10:00:00+08:00", remark: "补测调整",
}, "t08-adjust-1");
const activePreview = await call(auto, "POST", `/api/v1/accounts/${createdA.id}/permanent-deletion/preview`);
note("active delete rejected", activePreview.status === 409, `status=${activePreview.status}`);
await ok(auto, "POST", `/api/v1/accounts/${createdA.id}/archive`);
const preview = await ok(auto, "POST", `/api/v1/accounts/${createdA.id}/permanent-deletion/preview`);
const ownerPeek = await call(owner, "POST", `/api/v1/accounts/${createdA.id}/permanent-deletion/preview`);
const ownerStats = await call(owner, "GET", `/api/v1/statistics?preset=MONTH&accountScope=SELECTED&accountIds=${createdA.id}`);
note("cross user delete hidden", ownerPeek.status === 404 && ownerStats.status === 404, `preview=${ownerPeek.status} stats=${ownerStats.status}`);
const removed = await call(auto, "POST", `/api/v1/accounts/${createdA.id}/permanent-deletion`, { previewToken: preview.previewToken });
const gone = await call(auto, "GET", `/api/v1/statistics?preset=CUSTOM&from=2024-01-01&to=2024-01-31&accountScope=SELECTED&accountIds=${createdA.id}`);
const counterpart = await ok(auto, "GET", "/api/v1/accounts");
const b = counterpart.items.find((item) => item.id === createdB.id);
note("scheme B delete", removed.status === 204 && gone.status === 404 && preview.transactionCount >= 5 && preview.transferCount === 1 && b && b.currentBalance === "50.00", `status=${removed.status} tx=${preview.transactionCount} transfer=${preview.transferCount} counterpart=${b && b.currentBalance}`);
await ok(auto, "POST", `/api/v1/accounts/${createdB.id}/archive`);
const previewB = await ok(auto, "POST", `/api/v1/accounts/${createdB.id}/permanent-deletion/preview`);
const removedB = await call(auto, "POST", `/api/v1/accounts/${createdB.id}/permanent-deletion`, { previewToken: previewB.previewToken });
note("counterpart removed", removedB.status === 204, `status=${removedB.status}`);

const ownerAfter = await ok(owner, "GET", "/api/v1/accounts");
const autoAfter = await ok(auto, "GET", "/api/v1/accounts");
const same = (left, right) => left.items.length === right.items.length && left.items.every((item, index) => item.id === right.items[index].id && item.currentBalance === right.items[index].currentBalance);
note("owner untouched", same(ownerBefore, ownerAfter), `accounts=${ownerAfter.items.length}`);
note("auto family balances restored", same(autoBefore, autoAfter), `accounts=${autoAfter.items.length}`);
if (findings.some((item) => !item.ok)) process.exit(1);
