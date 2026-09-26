import { validateBusinessTime } from "./business-time";

const MONEY_RESPONSE_PATTERN = /^-?[0-9]+\.[0-9]{2}$/;

export type TransactionType = "INCOME" | "EXPENSE";

export interface CategoryRef {
  readonly id: number;
  readonly name: string;
}

export interface TagRef {
  readonly id: number;
  readonly name: string;
}

export interface TransactionView {
  readonly id: number;
  readonly accountId: number;
  readonly type: TransactionType;
  readonly amount: string;
  readonly name: string | null;
  readonly displayName: string | null;
  readonly category: CategoryRef | null;
  readonly tags: readonly TagRef[];
  readonly businessTime: string;
  readonly remark: string | null;
}

export function parseTransactionResponse(value: unknown): TransactionView | null {
  if (!isRecord(value) || !isPositiveId(value.id) || !isPositiveId(value.accountId)) {
    return null;
  }
  if (value.type !== "INCOME" && value.type !== "EXPENSE") {
    return null;
  }
  if (!isMoney(value.amount) || typeof value.businessTime !== "string" || !validateBusinessTime(value.businessTime) || !Array.isArray(value.tags)) {
    return null;
  }
  const name = optionalText(value.name);
  const displayName = optionalText(value.displayName);
  const remark = optionalText(value.remark);
  const category = parseCategoryRef(value.category);
  if (name === undefined || displayName === undefined || remark === undefined || category === undefined) {
    return null;
  }
  const tags: TagRef[] = [];
  for (const item of value.tags) {
    const tag = parseTagRef(item);
    if (tag === null) {
      return null;
    }
    tags.push(tag);
  }
  return {
    id: value.id,
    accountId: value.accountId,
    type: value.type,
    amount: value.amount,
    name,
    displayName,
    category,
    tags,
    businessTime: value.businessTime,
    remark,
  };
}

function parseCategoryRef(value: unknown): CategoryRef | null | undefined {
  if (value === undefined || value === null) {
    return null;
  }
  if (!isRecord(value) || !isPositiveId(value.id) || !isNonEmptyString(value.name)) {
    return undefined;
  }
  return { id: value.id, name: value.name };
}

function parseTagRef(value: unknown): TagRef | null {
  if (!isRecord(value) || !isPositiveId(value.id) || !isNonEmptyString(value.name)) {
    return null;
  }
  return { id: value.id, name: value.name };
}

function optionalText(value: unknown): string | null | undefined {
  if (value === undefined || value === null) {
    return null;
  }
  if (typeof value !== "string") {
    return undefined;
  }
  return value;
}

function isMoney(value: unknown): value is string {
  return typeof value === "string" && MONEY_RESPONSE_PATTERN.test(value);
}

function isPositiveId(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === "string" && value.length > 0;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}
