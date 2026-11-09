import { moneyToneClass } from "../account/money-tone";
import { canApplyLoadResult, type LoadGuard } from "../account/load-guard";

const MONEY_RESPONSE_PATTERN = /^-?[0-9]+\.[0-9]{2}$/;
const MONTH_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/;
const BUSINESS_TIME_PATTERN = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):\d{2}\+08:00$/;

export interface LedgerCategory {
  readonly id: number;
  readonly name: string;
}

export interface LedgerTag {
  readonly id: number;
  readonly name: string;
}

export interface LedgerSummary {
  readonly transactionCount: number;
  readonly netIncome: string;
  readonly netExpense: string;
  readonly surplus: string;
}

export interface TransactionEntry {
  readonly kind: "TRANSACTION";
  readonly id: number;
  readonly transactionType: "INCOME" | "EXPENSE";
  readonly displayName: string | null;
  readonly amount: string;
  readonly category: LedgerCategory | null;
  readonly tags: readonly LedgerTag[];
  readonly businessTime: string;
}

export interface BalanceAdjustmentEntry {
  readonly kind: "BALANCE_ADJUSTMENT";
  readonly id: number;
  readonly displayName: string;
  readonly amount: string;
  readonly businessTime: string;
}

export interface TransferEntry {
  readonly kind: "TRANSFER";
  readonly id: number;
  readonly displayName: string;
  readonly amount: string;
  readonly businessTime: string;
}

export type LedgerEntry = TransactionEntry | BalanceAdjustmentEntry | TransferEntry;

export interface Ledger {
  readonly accountId: number;
  readonly month: string | null;
  readonly currentBalance: string;
  readonly summary: LedgerSummary;
  readonly items: readonly LedgerEntry[];
  readonly nextCursor: string | null;
}

export interface LedgerRow {
  readonly recordId: number;
  readonly key: string;
  readonly kind: "transaction" | "adjustment" | "transfer";
  readonly title: string;
  readonly detail: string;
  readonly typeLabel: string;
  readonly amountLabel: string;
  readonly amountToneClass: string;
  readonly timeLabel: string;
  readonly dayKey: string;
}

export function parseLedger(value: unknown): Ledger | null {
  if (!isRecord(value) || !isRecord(value.summary) || !Array.isArray(value.items)) {
    return null;
  }
  const month = parseOptionalMonth(value.month);
  const nextCursor = parseNextCursor(value.nextCursor);
  if (!isPositiveId(value.accountId) || month === undefined || nextCursor === undefined) {
    return null;
  }
  if (!isMoney(value.currentBalance)) {
    return null;
  }
  const summary = parseSummary(value.summary);
  if (summary === null) {
    return null;
  }
  const items: LedgerEntry[] = [];
  for (const item of value.items) {
    const entry = parseEntry(item);
    if (entry === null) {
      return null;
    }
    items.push(entry);
  }
  return {
    accountId: value.accountId,
    month,
    currentBalance: value.currentBalance,
    summary,
    items,
    nextCursor,
  };
}

export function formatBusinessTime(value: string): string {
  const match = BUSINESS_TIME_PATTERN.exec(value);
  if (match === null) {
    return value;
  }
  return `${match[2]}-${match[3]} ${match[4]}:${match[5]}`;
}

export function businessDayKey(value: string): string {
  const match = BUSINESS_TIME_PATTERN.exec(value);
  if (match === null) {
    return "";
  }
  return `${match[1]}-${match[2]}-${match[3]}`;
}

export function formatMoneyLabel(amount: string): string {
  return `¥${amount}`;
}

export function ledgerBalanceEffectAmount(item: LedgerEntry): string {
  if (item.kind === "TRANSACTION" && item.transactionType === "EXPENSE") {
    return negateCanonicalMoney(item.amount);
  }
  return item.amount;
}

export function ledgerRows(items: readonly LedgerEntry[]): LedgerRow[] {
  return items.map((item) => {
    const effect = ledgerBalanceEffectAmount(item);
    if (item.kind === "TRANSFER") {
      return {
        recordId: item.id,
        key: `transfer-${item.id}`,
        kind: "transfer",
        title: item.displayName,
        detail: "",
        typeLabel: effect.startsWith("-") ? "转出" : "转入",
        amountLabel: formatMoneyLabel(effect),
        amountToneClass: moneyToneClass(effect),
        timeLabel: formatBusinessTime(item.businessTime),
        dayKey: businessDayKey(item.businessTime),
      };
    }
    if (item.kind === "BALANCE_ADJUSTMENT") {
      return {
        recordId: item.id,
        key: `adjustment-${item.id}`,
        kind: "adjustment",
        title: item.displayName,
        detail: "",
        typeLabel: "余额调整",
        amountLabel: formatMoneyLabel(effect),
        amountToneClass: moneyToneClass(effect),
        timeLabel: formatBusinessTime(item.businessTime),
        dayKey: businessDayKey(item.businessTime),
      };
    }
    const tags = item.tags.map((tag) => tag.name).join(" ");
    const category = item.category === null ? "未分类" : item.category.name;
    return {
      recordId: item.id,
      key: `transaction-${item.id}`,
      kind: "transaction",
      title: item.displayName === null || item.displayName.length === 0 ? "未命名交易" : item.displayName,
      detail: tags.length === 0 ? category : `${category} ${tags}`,
      typeLabel: item.transactionType === "INCOME" ? "收入" : "支出",
      amountLabel: formatMoneyLabel(effect),
      amountToneClass: moneyToneClass(effect),
      timeLabel: formatBusinessTime(item.businessTime),
      dayKey: businessDayKey(item.businessTime),
    };
  });
}

function negateCanonicalMoney(amount: string): string {
  if (amount === "0.00" || amount === "-0.00") {
    return "0.00";
  }
  return amount.startsWith("-") ? amount.slice(1) : `-${amount}`;
}

export function canApplyLedgerResult(
  guard: LoadGuard,
  generation: number,
  requestedMonth: string,
  selectedMonth: string,
): boolean {
  return canApplyLoadResult(guard, generation) && requestedMonth === selectedMonth;
}

export function ledgerPath(accountId: number, month: string): string {
  return `/api/v1/accounts/${accountId}/ledger?month=${month}`;
}

export interface LedgerPageQuery {
  readonly accountId: number;
  readonly month?: string | null;
  readonly from?: string | null;
  readonly to?: string | null;
  readonly name?: string | null;
  readonly cursor?: string | null;
}

export function ledgerPagePath(query: LedgerPageQuery): string {
  const params = ["paged=true"];
  if (query.month) params.push(`month=${query.month}`);
  if (query.from) params.push(`from=${query.from}`);
  if (query.to) params.push(`to=${query.to}`);
  if (query.name) params.push(`name=${encodeURIComponent(query.name)}`);
  if (query.cursor) params.push(`cursor=${encodeURIComponent(query.cursor)}`);
  return `/api/v1/accounts/${query.accountId}/ledger?${params.join("&")}`;
}

function parseOptionalMonth(value: unknown): string | null | undefined {
  if (value === null || value === undefined) return null;
  return typeof value === "string" && MONTH_PATTERN.test(value) ? value : undefined;
}

function parseNextCursor(value: unknown): string | null | undefined {
  if (value === null || value === undefined) return null;
  if (typeof value !== "string" || value.length === 0 || value.length > 400 || /[\r\n]/.test(value)) return undefined;
  return value;
}

function parseSummary(value: Record<string, unknown>): LedgerSummary | null {
  if (!isCount(value.transactionCount) || !isMoney(value.netIncome) || !isMoney(value.netExpense) || !isMoney(value.surplus)) {
    return null;
  }
  return {
    transactionCount: value.transactionCount,
    netIncome: value.netIncome,
    netExpense: value.netExpense,
    surplus: value.surplus,
  };
}

function parseEntry(value: unknown): LedgerEntry | null {
  if (!isRecord(value) || !isPositiveId(value.id) || !isBusinessTime(value.businessTime) || !isMoney(value.amount)) {
    return null;
  }
  if (value.kind === "BALANCE_ADJUSTMENT") {
    if (typeof value.displayName !== "string" || value.displayName.length === 0) {
      return null;
    }
    return {
      kind: "BALANCE_ADJUSTMENT",
      id: value.id,
      displayName: value.displayName,
      amount: value.amount,
      businessTime: value.businessTime,
    };
  }
  if (value.kind === "TRANSFER") {
    if (typeof value.displayName !== "string" || value.displayName.length === 0) {
      return null;
    }
    return {
      kind: "TRANSFER",
      id: value.id,
      displayName: value.displayName,
      amount: value.amount,
      businessTime: value.businessTime,
    };
  }
  if (value.kind !== "TRANSACTION") {
    return null;
  }
  if (value.transactionType !== "INCOME" && value.transactionType !== "EXPENSE") {
    return null;
  }
  const displayName = value.displayName === undefined || value.displayName === null ? null : value.displayName;
  if (displayName !== null && typeof displayName !== "string") {
    return null;
  }
  const category = parseCategory(value.category);
  const tags = parseTags(value.tags);
  if (category === undefined || tags === null) {
    return null;
  }
  return {
    kind: "TRANSACTION",
    id: value.id,
    transactionType: value.transactionType,
    displayName,
    amount: value.amount,
    category,
    tags,
    businessTime: value.businessTime,
  };
}

function parseCategory(value: unknown): LedgerCategory | null | undefined {
  if (value === undefined || value === null) {
    return null;
  }
  if (!isRecord(value) || !isPositiveId(value.id) || typeof value.name !== "string" || value.name.length === 0) {
    return undefined;
  }
  return { id: value.id, name: value.name };
}

function parseTags(value: unknown): LedgerTag[] | null {
  if (!Array.isArray(value)) {
    return null;
  }
  const tags: LedgerTag[] = [];
  for (const item of value) {
    if (!isRecord(item) || !isPositiveId(item.id) || typeof item.name !== "string" || item.name.length === 0) {
      return null;
    }
    tags.push({ id: item.id, name: item.name });
  }
  return tags;
}

function isBusinessTime(value: unknown): value is string {
  return typeof value === "string" && BUSINESS_TIME_PATTERN.test(value);
}

function isMoney(value: unknown): value is string {
  return typeof value === "string" && MONEY_RESPONSE_PATTERN.test(value);
}

function isCount(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}

function isPositiveId(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}
