const MONEY = /^-?[0-9]+\.[0-9]{2}$/;
const TIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\+08:00$/;

export type LedgerFeedScope = "ACTIVE" | "ALL" | "SELECTED";
export type LedgerFeedKind = "TRANSACTION" | "TRANSFER" | "BALANCE_ADJUSTMENT";

export interface LedgerFeedQuery {
  readonly month?: string;
  readonly from?: string;
  readonly to?: string;
  readonly name?: string;
  readonly accountScope: LedgerFeedScope;
  readonly accountIds?: readonly number[];
  readonly cursor?: string;
}

export interface LedgerFeedSummary {
  readonly netIncome: string;
  readonly netExpense: string;
  readonly surplus: string;
}

export interface LedgerFeedItem {
  readonly kind: LedgerFeedKind;
  readonly id: number;
  readonly transactionType: "INCOME" | "EXPENSE" | null;
  readonly displayName: string | null;
  readonly amount: string;
  readonly businessTime: string;
  readonly accountId: number;
  readonly accountName: string;
  readonly accountCreatedTime: string;
}

export interface LedgerFeedPage {
  readonly label: string | null;
  readonly summary: LedgerFeedSummary | null;
  readonly items: readonly LedgerFeedItem[];
  readonly nextCursor: string | null;
}

export function ledgerFeedPath(query: LedgerFeedQuery): string {
  const params = [`accountScope=${query.accountScope}`];
  if (query.month !== undefined) params.push(`month=${query.month}`);
  if (query.from !== undefined) params.push(`from=${query.from}`);
  if (query.to !== undefined) params.push(`to=${query.to}`);
  if (query.name !== undefined && query.name.length > 0) params.push(`name=${encodeURIComponent(query.name)}`);
  if (query.accountIds !== undefined && query.accountIds.length > 0) params.push(`accountIds=${query.accountIds.join(",")}`);
  if (query.cursor !== undefined) params.push(`cursor=${encodeURIComponent(query.cursor)}`);
  return `/api/v1/ledger?${params.join("&")}`;
}

export function allTransactionsPath(query: {
  month?: string;
  from?: string;
  to?: string;
  accountScope?: LedgerFeedScope;
  accountIds?: string;
}): string {
  const params: string[] = [];
  if (query.month !== undefined) params.push(`month=${query.month}`);
  if (query.from !== undefined) params.push(`from=${query.from}`);
  if (query.to !== undefined) params.push(`to=${query.to}`);
  if (query.accountScope !== undefined) params.push(`accountScope=${query.accountScope}`);
  if (query.accountIds !== undefined && query.accountIds.length > 0) params.push(`accountIds=${query.accountIds}`);
  const suffix = params.length === 0 ? "" : `?${params.join("&")}`;
  return `/pages/all-transactions/index${suffix}`;
}

export function parseLedgerFeed(body: unknown): LedgerFeedPage | null {
  if (!isRecord(body) || !Array.isArray(body.items)) return null;
  const label = body.label === undefined || body.label === null ? null : body.label;
  if (label !== null && typeof label !== "string") return null;
  const summary = body.summary === undefined || body.summary === null ? null : parseSummary(body.summary);
  if (body.summary !== undefined && body.summary !== null && summary === null) return null;
  const items: LedgerFeedItem[] = [];
  for (const item of body.items) {
    const parsed = parseItem(item);
    if (parsed === null) return null;
    items.push(parsed);
  }
  const nextCursor = body.nextCursor === undefined || body.nextCursor === null ? null : body.nextCursor;
  if (nextCursor !== null && typeof nextCursor !== "string") return null;
  return { label, summary, items, nextCursor };
}

function parseSummary(value: unknown): LedgerFeedSummary | null {
  if (!isRecord(value) || !isMoney(value.netIncome) || !isMoney(value.netExpense) || !isMoney(value.surplus)) return null;
  return { netIncome: value.netIncome, netExpense: value.netExpense, surplus: value.surplus };
}

function parseItem(value: unknown): LedgerFeedItem | null {
  if (!isRecord(value)) return null;
  const kind = value.kind;
  if (kind !== "TRANSACTION" && kind !== "TRANSFER" && kind !== "BALANCE_ADJUSTMENT") return null;
  if (!isPositive(value.id) || !isPositive(value.accountId) || typeof value.accountName !== "string") return null;
  if (!isMoney(value.amount) || !isTime(value.businessTime) || !isTime(value.accountCreatedTime)) return null;
  const transactionType = value.transactionType === null || value.transactionType === undefined
    ? null
    : value.transactionType === "INCOME" || value.transactionType === "EXPENSE" ? value.transactionType : undefined;
  if (transactionType === undefined) return null;
  const displayName = value.displayName === null || value.displayName === undefined
    ? null
    : typeof value.displayName === "string" ? value.displayName : undefined;
  if (displayName === undefined) return null;
  return {
    kind,
    id: value.id,
    transactionType,
    displayName,
    amount: value.amount,
    businessTime: value.businessTime,
    accountId: value.accountId,
    accountName: value.accountName,
    accountCreatedTime: value.accountCreatedTime,
  };
}

function isMoney(value: unknown): value is string {
  return typeof value === "string" && MONEY.test(value);
}

function isTime(value: unknown): value is string {
  return typeof value === "string" && TIME.test(value);
}

function isPositive(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
