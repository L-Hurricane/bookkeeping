const MONEY = /^-?[0-9]+\.[0-9]{2}$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const TIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\+08:00$/;
const SHARE = /^-?[0-9]+\.[0-9]{2}$/;

export type LedgerPreset = "MONTH" | "QUARTER" | "YEAR" | "CUSTOM";
export type LedgerAccountScope = "ACTIVE" | "ALL" | "SELECTED";
export type LedgerFlowType = "INCOME" | "EXPENSE";
export type LedgerTagMode = "TREE" | "DIRECT";

export interface LedgerAccount {
  readonly id: number;
  readonly name: string;
  readonly lifecycle: "ACTIVE" | "ARCHIVED";
}

export interface LedgerPoint {
  readonly date: string;
  readonly amount: string;
}

export interface LedgerNetWorth {
  readonly opening: string;
  readonly closing: string;
  readonly change: string;
  readonly points: readonly LedgerPoint[];
}

export interface LedgerSlice {
  readonly transactionCount: number;
  readonly amount: string;
  readonly share: string | null;
}

export interface LedgerCategoryNode {
  readonly id: number;
  readonly name: string;
  readonly transactionCount: number;
  readonly amount: string;
  readonly share: string | null;
  readonly children: readonly LedgerCategoryNode[];
}

export interface LedgerTagNode {
  readonly id: number;
  readonly name: string;
  readonly transactionCount: number;
  readonly amount: string;
  readonly share: string | null;
  readonly children: readonly LedgerTagNode[];
}

export interface LedgerFlow {
  readonly net: string;
  readonly categories: readonly LedgerCategoryNode[];
  readonly tags: readonly LedgerTagNode[];
  readonly uncategorized: LedgerSlice | null;
  readonly untagged: LedgerSlice | null;
}

export interface LedgerReport {
  readonly preset: LedgerPreset;
  readonly from: string;
  readonly to: string;
  readonly accountScope: LedgerAccountScope;
  readonly accounts: readonly LedgerAccount[];
  readonly netWorth: LedgerNetWorth;
  readonly income: LedgerFlow;
  readonly expense: LedgerFlow;
}

export interface LedgerTransaction {
  readonly id: number;
  readonly accountId: number;
  readonly accountCreatedTime: string;
  readonly type: LedgerFlowType;
  readonly amount: string;
  readonly displayName: string | null;
  readonly businessTime: string;
}

export interface LedgerTransactionList {
  readonly items: readonly LedgerTransaction[];
}

export interface LedgerQuery {
  readonly preset: LedgerPreset;
  readonly from?: string;
  readonly to?: string;
  readonly accountScope: LedgerAccountScope;
  readonly accountIds?: readonly number[];
}

export type LedgerDrill =
  | { readonly kind: "category"; readonly categoryId: number; readonly title: string }
  | { readonly kind: "uncategorized"; readonly title: string }
  | { readonly kind: "tag"; readonly tagId: number; readonly title: string; readonly tagMode: LedgerTagMode }
  | { readonly kind: "tags"; readonly tagIds: readonly number[]; readonly title: string }
  | { readonly kind: "untagged"; readonly title: string };

export interface LedgerDetailContext {
  readonly query: LedgerQuery;
  readonly type: LedgerFlowType;
  readonly drill: LedgerDrill;
}

export function statisticsReportPath(query: LedgerQuery): string {
  return `/api/v1/statistics?${queryString(query)}`;
}

export function statisticsTransactionPath(context: LedgerDetailContext): string {
  const drill = drillQuery(context.drill);
  return `/api/v1/statistics/transactions?${queryString(context.query)}&type=${context.type}&${drill}`;
}

export function statisticsDetailPagePath(context: LedgerDetailContext): string {
  return `/pages/statistics-detail/index?${queryString(context.query)}&type=${context.type}&${drillQuery(context.drill)}&title=${encodeURIComponent(context.drill.title)}`;
}

export function parseLedgerReport(value: unknown): LedgerReport | null {
  if (!isRecord(value) || !isPreset(value.preset) || !isDate(value.from) || !isDate(value.to) || !isScope(value.accountScope)) {
    return null;
  }
  const accounts = parseAccounts(value.accounts);
  const netWorth = parseNetWorth(value.netWorth);
  const income = parseFlow(value.income);
  const expense = parseFlow(value.expense);
  if (accounts === null || netWorth === null || income === null || expense === null) return null;
  return { preset: value.preset, from: value.from, to: value.to, accountScope: value.accountScope, accounts, netWorth, income, expense };
}

export function parseLedgerTransactions(value: unknown): LedgerTransactionList | null {
  if (!isRecord(value) || !Array.isArray(value.items)) return null;
  const items: LedgerTransaction[] = [];
  for (const item of value.items) {
    const parsed = parseTransaction(item);
    if (parsed === null) return null;
    items.push(parsed);
  }
  return { items };
}

export function parseLedgerDetailContext(query: Record<string, string | undefined>): LedgerDetailContext | null {
  if (!isPreset(query.preset) || !isScope(query.accountScope) || (query.type !== "INCOME" && query.type !== "EXPENSE")) {
    return null;
  }
  const ledgerQuery = parseQuery(query);
  const drill = parseDrill(query);
  if (ledgerQuery === null || drill === null) return null;
  return { query: ledgerQuery, type: query.type, drill };
}

function parseQuery(query: Record<string, string | undefined>): LedgerQuery | null {
  if (!isPreset(query.preset) || !isScope(query.accountScope)) return null;
  if (query.preset === "CUSTOM" && (!isDate(query.from) || !isDate(query.to))) return null;
  if (query.accountScope !== "SELECTED") {
    return { preset: query.preset, from: query.from, to: query.to, accountScope: query.accountScope };
  }
  const accountIds = parseIdList(query.accountIds);
  if (accountIds === null || accountIds.length === 0) return null;
  return { preset: query.preset, from: query.from, to: query.to, accountScope: "SELECTED", accountIds };
}

function parseDrill(query: Record<string, string | undefined>): LedgerDrill | null {
  const title = query.title;
  if (title === undefined || title.length === 0) return null;
  if (query.uncategorized === "true") return { kind: "uncategorized", title };
  if (query.untagged === "true") return { kind: "untagged", title };
  if (query.tagIds !== undefined && query.tagId !== undefined) return null;
  const tagIds = parseIdList(query.tagIds);
  if (tagIds !== null) return { kind: "tags", tagIds, title };
  const categoryId = parsePositive(query.categoryId);
  if (categoryId !== null && query.tagId === undefined && query.uncategorized === undefined) {
    return { kind: "category", categoryId, title };
  }
  const tagId = parsePositive(query.tagId);
  if (tagId !== null && (query.tagMode === "TREE" || query.tagMode === "DIRECT")) {
    return { kind: "tag", tagId, title, tagMode: query.tagMode };
  }
  return null;
}

function queryString(query: LedgerQuery): string {
  const params = [`preset=${query.preset}`, `accountScope=${query.accountScope}`];
  if (query.preset === "CUSTOM" && query.from !== undefined && query.to !== undefined) {
    params.push(`from=${query.from}`, `to=${query.to}`);
  }
  if (query.accountScope === "SELECTED" && query.accountIds !== undefined) {
    params.push(`accountIds=${query.accountIds.join(",")}`);
  }
  return params.join("&");
}

function drillQuery(drill: LedgerDrill): string {
  switch (drill.kind) {
    case "category":
      return `drill=category&categoryId=${drill.categoryId}`;
    case "uncategorized":
      return "drill=uncategorized&uncategorized=true";
    case "tag":
      return `drill=tag&tagId=${drill.tagId}&tagMode=${drill.tagMode}`;
    case "tags":
      return `tagIds=${drill.tagIds.join(",")}`;
    case "untagged":
      return "drill=untagged&untagged=true";
  }
}

function parseAccounts(value: unknown): LedgerAccount[] | null {
  if (!Array.isArray(value)) return null;
  const accounts: LedgerAccount[] = [];
  for (const item of value) {
    if (!isRecord(item) || !isPositive(item.id) || typeof item.name !== "string" || (item.lifecycle !== "ACTIVE" && item.lifecycle !== "ARCHIVED")) {
      return null;
    }
    accounts.push({ id: item.id, name: item.name, lifecycle: item.lifecycle });
  }
  return accounts;
}

function parseNetWorth(value: unknown): LedgerNetWorth | null {
  if (!isRecord(value) || !isMoney(value.opening) || !isMoney(value.closing) || !isMoney(value.change) || !Array.isArray(value.points)) {
    return null;
  }
  const points: LedgerPoint[] = [];
  for (const point of value.points) {
    if (!isRecord(point) || !isDate(point.date) || !isMoney(point.amount)) return null;
    points.push({ date: point.date, amount: point.amount });
  }
  return { opening: value.opening, closing: value.closing, change: value.change, points };
}

function parseFlow(value: unknown): LedgerFlow | null {
  if (!isRecord(value) || !isMoney(value.net) || !Array.isArray(value.categories) || !Array.isArray(value.tags)) return null;
  const categories: LedgerCategoryNode[] = [];
  for (const node of value.categories) {
    const parsed = parseCategory(node);
    if (parsed === null) return null;
    categories.push(parsed);
  }
  const tags: LedgerTagNode[] = [];
  for (const node of value.tags) {
    const parsed = parseTag(node);
    if (parsed === null) return null;
    tags.push(parsed);
  }
  const uncategorized = parseSlice(value.uncategorized);
  const untagged = parseSlice(value.untagged);
  if (uncategorized === undefined || untagged === undefined) return null;
  return { net: value.net, categories, tags, uncategorized, untagged };
}

function parseCategory(value: unknown): LedgerCategoryNode | null {
  const share = readShare(isRecord(value) ? value.share : undefined);
  if (!isRecord(value) || !isPositive(value.id) || typeof value.name !== "string" || !isCount(value.transactionCount) || !isMoney(value.amount) || share === undefined || !Array.isArray(value.children)) {
    return null;
  }
  const children: LedgerCategoryNode[] = [];
  for (const child of value.children) {
    const parsed = parseCategory(child);
    if (parsed === null) return null;
    children.push(parsed);
  }
  return { id: value.id, name: value.name, transactionCount: value.transactionCount, amount: value.amount, share, children };
}

function parseTag(value: unknown): LedgerTagNode | null {
  const share = readShare(isRecord(value) ? value.share : undefined);
  if (!isRecord(value) || !isPositive(value.id) || typeof value.name !== "string" || !isCount(value.transactionCount) || !isMoney(value.amount) || share === undefined || !Array.isArray(value.children)) {
    return null;
  }
  const children: LedgerTagNode[] = [];
  for (const child of value.children) {
    const parsed = parseTag(child);
    if (parsed === null) return null;
    children.push(parsed);
  }
  return { id: value.id, name: value.name, transactionCount: value.transactionCount, amount: value.amount, share, children };
}

function parseSlice(value: unknown): LedgerSlice | null | undefined {
  if (value === undefined || value === null) return null;
  const share = readShare(isRecord(value) ? value.share : undefined);
  if (!isRecord(value) || !isCount(value.transactionCount) || !isMoney(value.amount) || share === undefined) return undefined;
  return { transactionCount: value.transactionCount, amount: value.amount, share };
}

function parseTransaction(value: unknown): LedgerTransaction | null {
  if (!isRecord(value) || !isPositive(value.id) || !isPositive(value.accountId) || !isTime(value.accountCreatedTime)) return null;
  if ((value.type !== "INCOME" && value.type !== "EXPENSE") || !isMoney(value.amount) || !isTime(value.businessTime)) return null;
  if (value.displayName !== undefined && value.displayName !== null && typeof value.displayName !== "string") return null;
  return {
    id: value.id,
    accountId: value.accountId,
    accountCreatedTime: value.accountCreatedTime,
    type: value.type,
    amount: value.amount,
    displayName: typeof value.displayName === "string" ? value.displayName : null,
    businessTime: value.businessTime,
  };
}

function parseIdList(value: string | undefined): number[] | null {
  if (value === undefined || value.length === 0) return null;
  const ids: number[] = [];
  for (const part of value.split(",")) {
    const id = parsePositive(part);
    if (id === null || ids.includes(id)) return null;
    ids.push(id);
  }
  return ids;
}

function parsePositive(value: string | undefined): number | null {
  if (value === undefined || !/^[1-9][0-9]*$/.test(value)) return null;
  const id = Number(value);
  return Number.isSafeInteger(id) ? id : null;
}

function isPreset(value: unknown): value is LedgerPreset {
  return value === "MONTH" || value === "QUARTER" || value === "YEAR" || value === "CUSTOM";
}

function isScope(value: unknown): value is LedgerAccountScope {
  return value === "ACTIVE" || value === "ALL" || value === "SELECTED";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isPositive(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}

function isCount(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}

function isMoney(value: unknown): value is string {
  return typeof value === "string" && MONEY.test(value);
}

function isDate(value: unknown): value is string {
  return typeof value === "string" && DATE.test(value);
}

function isTime(value: unknown): value is string {
  return typeof value === "string" && TIME.test(value);
}

function readShare(value: unknown): string | null | undefined {
  if (value === undefined || value === null) return null;
  return typeof value === "string" && SHARE.test(value) ? value : undefined;
}
