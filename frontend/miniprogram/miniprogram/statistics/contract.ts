import { moneyEqual, subtractMoneyStrings } from "../balance-adjustment/money";
import { businessMonth, isBusinessTimeAllowed, validateBusinessTime } from "../transaction/business-time";

const MONEY_PATTERN = /^-?[0-9]+\.[0-9]{2}$/;
const MONTH_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/;

export type StatisticsType = "INCOME" | "EXPENSE";

export interface MonthlyStatistics {
  readonly month: string;
  readonly transactionCount: number;
  readonly netIncome: string;
  readonly netExpense: string;
  readonly surplus: string;
}

export interface CategoryChildStatistics {
  readonly categoryId: number;
  readonly categoryName: string;
  readonly transactionCount: number;
  readonly amount: string;
}

export interface CategoryStatisticsItem {
  readonly kind: "CATEGORY" | "UNCATEGORIZED";
  readonly categoryId: number | null;
  readonly categoryName: string;
  readonly transactionCount: number;
  readonly amount: string;
  readonly children: readonly CategoryChildStatistics[];
}

export interface CategoryStatistics {
  readonly month: string;
  readonly type: StatisticsType;
  readonly items: readonly CategoryStatisticsItem[];
}

export interface TagStatisticsItem {
  readonly kind: "TAG" | "UNTAGGED";
  readonly tagId: number | null;
  readonly tagName: string;
  readonly transactionCount: number;
  readonly amount: string;
}

export interface TagStatistics {
  readonly month: string;
  readonly type: StatisticsType;
  readonly overlap: true;
  readonly items: readonly TagStatisticsItem[];
}

export interface StatisticsBundle {
  readonly monthly: MonthlyStatistics;
  readonly categories: CategoryStatistics;
  readonly tags: TagStatistics;
}

export interface TransactionListCategory {
  readonly id: number;
  readonly name: string;
}

export interface TransactionListTag {
  readonly id: number;
  readonly name: string;
}

export interface TransactionListItem {
  readonly id: number;
  readonly type: StatisticsType;
  readonly amount: string;
  readonly displayName: string | null;
  readonly category: TransactionListCategory | null;
  readonly tags: readonly TransactionListTag[];
  readonly businessTime: string;
}

export interface TransactionList {
  readonly month: string;
  readonly items: readonly TransactionListItem[];
}

export function parseMonthlyStatistics(value: unknown, month: string): MonthlyStatistics | null {
  if (!isRecord(value) || value.month !== month) {
    return null;
  }
  if (!isCount(value.transactionCount) || !isMoney(value.netIncome) || !isMoney(value.netExpense) || !isMoney(value.surplus)) {
    return null;
  }
  const surplus = subtractMoneyStrings(value.netIncome, value.netExpense);
  if (surplus === null || !moneyEqual(surplus, value.surplus)) {
    return null;
  }
  return {
    month,
    transactionCount: value.transactionCount,
    netIncome: value.netIncome,
    netExpense: value.netExpense,
    surplus: value.surplus,
  };
}

export function parseCategoryStatistics(value: unknown, month: string, type: StatisticsType): CategoryStatistics | null {
  if (!isRecord(value) || value.month !== month || value.type !== type || !Array.isArray(value.items)) {
    return null;
  }
  const items: CategoryStatisticsItem[] = [];
  for (const item of value.items) {
    const parsed = parseCategoryItem(item);
    if (parsed === null) {
      return null;
    }
    items.push(parsed);
  }
  return { month, type, items };
}

export function parseTagStatistics(value: unknown, month: string, type: StatisticsType): TagStatistics | null {
  if (!isRecord(value) || value.month !== month || value.type !== type || value.overlap !== true || !Array.isArray(value.items)) {
    return null;
  }
  const items: TagStatisticsItem[] = [];
  for (const item of value.items) {
    const parsed = parseTagItem(item);
    if (parsed === null) {
      return null;
    }
    items.push(parsed);
  }
  return { month, type, overlap: true, items };
}

export function parseTransactionList(value: unknown, month: string, type: StatisticsType, accountCreatedTime: string): TransactionList | null {
  if (!isRecord(value) || value.month !== month || !Array.isArray(value.items)) {
    return null;
  }
  const items: TransactionListItem[] = [];
  for (const item of value.items) {
    const parsed = parseTransactionItem(item, type, month, accountCreatedTime);
    if (parsed === null) {
      return null;
    }
    items.push(parsed);
  }
  return { month, items };
}

function parseCategoryItem(value: unknown): CategoryStatisticsItem | null {
  if (!isRecord(value) || !isCount(value.transactionCount) || !isMoney(value.amount) || !isName(value.categoryName)) {
    return null;
  }
  const children = parseChildren(value.children);
  if (children === null) {
    return null;
  }
  if (value.kind === "UNCATEGORIZED") {
    if (value.categoryId !== undefined && value.categoryId !== null) {
      return null;
    }
    if (value.categoryName !== "未分类" || children.length > 0) {
      return null;
    }
    return {
      kind: "UNCATEGORIZED",
      categoryId: null,
      categoryName: value.categoryName,
      transactionCount: value.transactionCount,
      amount: value.amount,
      children,
    };
  }
  if (value.kind !== "CATEGORY" || !isPositiveId(value.categoryId)) {
    return null;
  }
  return {
    kind: "CATEGORY",
    categoryId: value.categoryId,
    categoryName: value.categoryName,
    transactionCount: value.transactionCount,
    amount: value.amount,
    children,
  };
}

function parseChildren(value: unknown): CategoryChildStatistics[] | null {
  if (value === undefined || value === null) {
    return [];
  }
  if (!Array.isArray(value)) {
    return null;
  }
  const children: CategoryChildStatistics[] = [];
  for (const item of value) {
    if (!isRecord(item) || "kind" in item || !isPositiveId(item.categoryId) || !isName(item.categoryName)) {
      return null;
    }
    if (!isCount(item.transactionCount) || !isMoney(item.amount)) {
      return null;
    }
    children.push({
      categoryId: item.categoryId,
      categoryName: item.categoryName,
      transactionCount: item.transactionCount,
      amount: item.amount,
    });
  }
  return children;
}

function parseTagItem(value: unknown): TagStatisticsItem | null {
  if (!isRecord(value) || !isName(value.tagName) || !isCount(value.transactionCount) || !isMoney(value.amount)) {
    return null;
  }
  const missingId = value.tagId === undefined || value.tagId === null;
  if (missingId) {
    if (value.tagName !== "无标签") {
      return null;
    }
    return { kind: "UNTAGGED", tagId: null, tagName: value.tagName, transactionCount: value.transactionCount, amount: value.amount };
  }
  if (!isPositiveId(value.tagId)) {
    return null;
  }
  return { kind: "TAG", tagId: value.tagId, tagName: value.tagName, transactionCount: value.transactionCount, amount: value.amount };
}

function parseTransactionItem(value: unknown, type: StatisticsType, month: string, accountCreatedTime: string): TransactionListItem | null {
  if (!isRecord(value) || !isPositiveId(value.id) || value.type !== type || !isMoney(value.amount)) {
    return null;
  }
  if (typeof value.businessTime !== "string" || !validateBusinessTime(value.businessTime) || businessMonth(value.businessTime) !== month) {
    return null;
  }
  if (!isBusinessTimeAllowed(value.businessTime, accountCreatedTime)) {
    return null;
  }
  const displayName = value.displayName === undefined || value.displayName === null ? null : value.displayName;
  if (displayName !== null && typeof displayName !== "string") {
    return null;
  }
  const category = parseListCategory(value.category);
  const tags = parseListTags(value.tags);
  if (category === undefined || tags === null) {
    return null;
  }
  return { id: value.id, type, amount: value.amount, displayName, category, tags, businessTime: value.businessTime };
}

function parseListCategory(value: unknown): TransactionListCategory | null | undefined {
  if (value === undefined || value === null) {
    return null;
  }
  if (!isRecord(value) || !isPositiveId(value.id) || !isName(value.name)) {
    return undefined;
  }
  return { id: value.id, name: value.name };
}

function parseListTags(value: unknown): TransactionListTag[] | null {
  if (!Array.isArray(value)) {
    return null;
  }
  const tags: TransactionListTag[] = [];
  for (const item of value) {
    if (!isRecord(item) || !isPositiveId(item.id) || !isName(item.name)) {
      return null;
    }
    tags.push({ id: item.id, name: item.name });
  }
  return tags;
}

function isMoney(value: unknown): value is string {
  return typeof value === "string" && MONEY_PATTERN.test(value);
}

function isCount(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}

function isPositiveId(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}

function isName(value: unknown): value is string {
  return typeof value === "string" && value.length > 0;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

export function isStatisticsMonth(value: string): boolean {
  return MONTH_PATTERN.test(value);
}
