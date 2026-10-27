/**
 * Fixed-seed fictional family ledger for bk_s6_family_e2e_20261027 only.
 * Version s6-family-e2e-v0.1, seed 20261027. Amounts are integer cents.
 * The events file is the oracle input. Do not treat a statistics response as expected.
 */
import { readFileSync, writeFileSync } from "node:fs";

const VERSION = "s6-family-e2e-v0.1";
const SEED = 20261027;
const SCHEMA = "bk_s6_family_e2e_20261027";
const ALLOWED = new Set(["s6_family_owner", "s6_family_auto"]);

const envPath = process.argv[2];
const factsPath = process.argv[3];
const username = process.argv[4];
if (!envPath || !factsPath || !ALLOWED.has(username)) {
  process.stderr.write("usage: generate.mjs <env-file> <facts.jsonl> <s6_family_owner|s6_family_auto>\n");
  process.exit(2);
}
const env = Object.fromEntries(readFileSync(envPath, "utf8").split("\n").filter((line) => line.includes("=")).map((line) => {
  const index = line.indexOf("=");
  return [line.slice(0, index), line.slice(index + 1)];
}));
const base = "http://127.0.0.1:18081";
if (!String(env.S6_FAMILY_DB_URL).includes(`/${SCHEMA}`)) {
  process.stderr.write("refusing a database url that is not the approved family schema\n");
  process.exit(2);
}

function mulberry32(seed) {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let value = state;
    value = Math.imul(value ^ (value >>> 15), value | 1);
    value ^= value + Math.imul(value ^ (value >>> 7), value | 61);
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296;
  };
}

function cents(yuan) {
  return Math.round(yuan * 100);
}
function parseCents(text) {
  const negative = text.startsWith("-");
  const [whole, fraction] = text.replace("-", "").split(".");
  return (negative ? -1 : 1) * (Number(whole) * 100 + Number(fraction ?? "0"));
}
function money(centsValue) {
  const sign = centsValue < 0 ? "-" : "";
  const abs = Math.abs(centsValue);
  return `${sign}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, "0")}`;
}
function time(year, month, day, hour, minute, second) {
  const pad = (value) => String(value).padStart(2, "0");
  return `${year}-${pad(month)}-${pad(day)}T${pad(hour)}:${pad(minute)}:${pad(second)}+08:00`;
}
function daysInMonth(year, month) {
  return new Date(Date.UTC(year, month, 0)).getUTCDate();
}

function buildEvents() {
  const events = [];
  const rand = mulberry32(SEED);
  const pick = (list) => list[Math.floor(rand() * list.length)];
  const months = [];
  for (let index = 0; index < 12; index += 1) {
    const cursor = new Date(Date.UTC(2025, 10 + index, 1));
    months.push({ year: cursor.getUTCFullYear(), month: cursor.getUTCMonth() + 1 });
  }
  const leaves = ["早餐", "午餐", "外卖", "超市", "房租", "水电", "通勤", "打车", "学费", "医疗", "购物", "人情"];
  for (const { year, month } of months) {
    const days = daysInMonth(year, month);
    const limit = year === 2026 && month === 10 ? 27 : days;
    const full = 450 + ((year * 12 + month) % 101);
    const count = year === 2026 && month === 10 ? Math.round(full * 27 / 31) : full;
    events.push({ kind: "INCOME", account: "银行", category: "工资", tags: ["入账"], amountCents: 1_800_000, name: "工资甲", businessTime: time(year, month, 5, 9, 0, 0) });
    events.push({ kind: "INCOME", account: "银行", category: "工资", tags: ["入账"], amountCents: 1_600_000, name: "工资乙", businessTime: time(year, month, 10, 9, 0, 0) });
    if (month % 3 === 0) {
      events.push({ kind: "INCOME", account: "支付宝", category: "稿费", tags: ["入账"], amountCents: 150_000 + (month * 10_000), name: "兼职", businessTime: time(year, month, Math.min(20, limit), 20, 0, 0) });
    }
    events.push({ kind: "EXPENSE", account: "银行", category: "房租", tags: ["日用"], amountCents: 450_000, name: "房租", businessTime: time(year, month, 1, 8, 0, 0) });
    for (let item = 0; item < count; item += 1) {
      const day = 1 + Math.floor(rand() * limit);
      const hour = Math.floor(rand() * 16) + 6;
      const minute = Math.floor(rand() * 60);
      const second = Math.floor(rand() * 60);
      const leaf = pick(leaves.filter((name) => name !== "房租"));
      const account = pick(["微信", "支付宝", "现金", "信用", "银行"]);
      const amountCents = 300 + Math.floor(rand() * 18_000);
      const tags = rand() < 0.15 ? [] : rand() < 0.25 ? ["孩子", "日用"] : [pick(["孩子", "日用", "工作"])];
      const category = rand() < 0.04 ? null : leaf;
      events.push({
        kind: "EXPENSE",
        account,
        category,
        tags,
        amountCents,
        name: category === null ? "未分类消费" : leaf,
        businessTime: time(year, month, day, hour, minute, second),
      });
    }
  }
  events.push(
    { kind: "EXPENSE", account: "微信", category: "午餐", tags: ["日用"], amountCents: 3200, name: "同秒甲", businessTime: "2026-01-15T12:00:00+08:00" },
    { kind: "EXPENSE", account: "微信", category: "午餐", tags: ["日用"], amountCents: 1800, name: "同秒乙", businessTime: "2026-01-15T12:00:00+08:00" },
    { kind: "EXPENSE", account: "现金", category: "早餐", tags: [], amountCents: 900, name: "同秒丙", businessTime: "2026-01-15T12:00:00+08:00" },
    { kind: "EXPENSE", account: "信用", category: "购物", tags: ["日用"], amountCents: 86_000, name: "信用消费", businessTime: "2026-06-18T15:00:00+08:00" },
    { kind: "EXPENSE", account: "归档", category: "人情", tags: ["日用"], amountCents: 20_000, name: "归档前支出", businessTime: "2026-02-02T11:00:00+08:00" },
    { kind: "TRANSFER", from: "银行", to: "微信", amountCents: 200_000, fromBusinessTime: "2026-03-31T22:00:00+08:00", toBusinessTime: "2026-04-01T08:00:00+08:00", name: "跨月转账" },
    { kind: "TRANSFER", from: "银行", to: "支付宝", amountCents: 150_000, fromBusinessTime: "2025-12-31T21:00:00+08:00", toBusinessTime: "2026-01-02T09:00:00+08:00", name: "跨年转账" },
    { kind: "ADJUSTMENT", account: "现金", deltaCents: -5_000, businessTime: "2026-05-06T10:00:00+08:00", name: "现金盘点" },
    { kind: "EXPENSE", account: "微信", category: "通勤", tags: ["工作"], amountCents: 600, name: "固定检索样本", businessTime: "2026-08-08T08:08:08+08:00" },
  );
  return events;
}

const events = buildEvents();
if (username === "s6_family_owner") {
  writeFileSync(factsPath, events.map((event) => JSON.stringify({ version: VERSION, seed: SEED, ...event })).join("\n") + "\n");
}

async function api(token, method, path, body, key) {
  const headers = { "content-type": "application/json" };
  if (token) headers.authorization = `Bearer ${token}`;
  if (key) headers["idempotency-key"] = key;
  const response = await fetch(`${base}${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await response.text();
  const payload = text.length === 0 ? null : JSON.parse(text);
  if (response.status >= 300) {
    process.stderr.write(`${method} ${path} ${response.status} ${text.slice(0, 300)}\n`);
    process.exit(1);
  }
  return payload;
}

const password = username === "s6_family_owner" ? env.S6_FAMILY_OWNER_PASSWORD : env.S6_FAMILY_AUTO_PASSWORD;
const login = await api(null, "POST", "/api/v1/auth/login", { username, password });
const token = login.token;
const accounts = {};
const openings = { 银行: "20000.00", 储蓄: "5000.00", 微信: "800.00", 支付宝: "300.00", 信用: "0.00", 现金: "500.00", 归档: "1000.00" };
const existingAccounts = await api(token, "GET", "/api/v1/accounts");
for (const row of existingAccounts.items) accounts[row.name] = row.id;
for (const [name, opening] of Object.entries(openings)) {
  if (accounts[name] !== undefined) continue;
  const created = await api(token, "POST", "/api/v1/accounts", { name, kind: name === "信用" ? "CREDIT" : "NORMAL", openingBalance: opening });
  accounts[name] = created.id;
}
const category = {};
function remember(type, nodes) {
  for (const node of nodes) {
    category[`${type}:${node.name}`] = node.id;
    remember(type, node.children ?? []);
  }
}
for (const type of ["EXPENSE", "INCOME"]) {
  remember(type, (await api(token, "GET", `/api/v1/categories?type=${type}`)).items);
}
async function addCategory(type, name, parentName) {
  const key = `${type}:${name}`;
  if (category[key] !== undefined) return;
  const created = await api(token, "POST", "/api/v1/categories", {
    type,
    name,
    parentId: parentName === undefined ? null : category[`${type}:${parentName}`],
  });
  category[key] = created.id;
}
await addCategory("EXPENSE", "外卖", "餐饮");
await addCategory("EXPENSE", "超市", "生活");
await addCategory("EXPENSE", "房租", "居住");
await addCategory("EXPENSE", "水电", "居住");
await addCategory("EXPENSE", "通勤", "交通");
await addCategory("EXPENSE", "教育");
await addCategory("EXPENSE", "学费", "教育");
await addCategory("EXPENSE", "人情", "生活");
await addCategory("INCOME", "兼职");
await addCategory("INCOME", "稿费", "兼职");
const tags = {};
function rememberTags(type, nodes) {
  for (const node of nodes) {
    tags[`${type}:${node.name}`] = node.id;
    rememberTags(type, node.children ?? []);
  }
}
for (const type of ["EXPENSE", "INCOME"]) {
  rememberTags(type, (await api(token, "GET", `/api/v1/tags?type=${type}`)).items);
}
async function addTag(type, name, parentName) {
  const key = `${type}:${name}`;
  if (tags[key] !== undefined) return;
  const created = await api(token, "POST", "/api/v1/tags", {
    type,
    name,
    parentId: parentName === undefined ? null : tags[`${type}:${parentName}`],
  });
  tags[key] = created.id;
}
await addTag("EXPENSE", "家庭");
await addTag("EXPENSE", "孩子", "家庭");
await addTag("EXPENSE", "日用", "家庭");
await addTag("EXPENSE", "工作");
await addTag("INCOME", "入账");

let sequence = 0;
function nextKey() {
  sequence += 1;
  return `e-${String(sequence).padStart(6, "0")}`;
}
for (const event of events) {
  if (event.kind === "INCOME" || event.kind === "EXPENSE") {
    const type = event.kind;
    await api(token, "POST", `/api/v1/accounts/${accounts[event.account]}/transactions`, {
      type,
      amount: money(event.amountCents),
      name: event.name,
      categoryId: event.category === null || event.category === undefined ? null : category[`${type}:${event.category}`],
      tagIds: event.tags.map((tag) => tags[`${type}:${tag}`]),
      businessTime: event.businessTime,
      remark: "",
    }, nextKey());
  } else if (event.kind === "TRANSFER") {
    await api(token, "POST", "/api/v1/transfers", {
      fromAccountId: accounts[event.from],
      toAccountId: accounts[event.to],
      amount: money(event.amountCents),
      fromBusinessTime: event.fromBusinessTime,
      toBusinessTime: event.toBusinessTime,
    }, nextKey());
  } else if (event.kind === "ADJUSTMENT") {
    const current = await api(token, "GET", "/api/v1/accounts");
    const row = current.items.find((item) => item.id === accounts[event.account]);
    const before = parseCents(row.currentBalance);
    const target = before + event.deltaCents;
    const created = await api(token, "POST", `/api/v1/accounts/${accounts[event.account]}/balance-adjustments`, {
      targetBalance: money(target),
      businessTime: event.businessTime,
      remark: event.name,
    }, nextKey());
    if (created.adjustment.adjustmentAmount !== money(event.deltaCents)) {
      process.stderr.write(`adjustment delta mismatch ${created.adjustment.adjustmentAmount}\n`);
      process.exit(1);
    }
  }
}
await api(token, "POST", `/api/v1/accounts/${accounts["归档"]}/archive`);
const expense = events.filter((event) => event.kind === "EXPENSE").length;
const income = events.filter((event) => event.kind === "INCOME").length;
process.stdout.write(`${username} events=${events.length} expense=${expense} income=${income} transfer=${events.filter((e) => e.kind === "TRANSFER").length} adjustment=${events.filter((e) => e.kind === "ADJUSTMENT").length}\n`);
