import { businessMonth, currentBeijingBusinessTime, validateBusinessTime } from "./business-time";
import type { TransactionType, TransactionView } from "./transaction";

const AMOUNT_PATTERN = /^[+-]?[0-9]+(\.[0-9]{1,2})?$/;
const ZERO_AMOUNT_PATTERN = /^[+-]?0+(?:\.0{1,2})?$/;

export interface CreateTransactionForm {
  readonly type: TransactionType;
  readonly amount: string;
  readonly name: string;
  readonly categoryId: number | null;
  readonly selectedTagIds: readonly number[];
  readonly businessTime: string;
  readonly remark: string;
}

export interface CreateTransactionSnapshot {
  readonly accountId: number;
  readonly type: TransactionType;
  readonly amount: string;
  readonly name: string | null;
  readonly categoryId: number | null;
  readonly tagIds: readonly number[];
  readonly businessTime: string;
  readonly remark: string | null;
}

export interface CreateTransactionRequestBody {
  readonly type: TransactionType;
  readonly amount: string;
  readonly name?: string;
  readonly categoryId?: number;
  readonly tagIds: readonly number[];
  readonly businessTime: string;
  readonly remark?: string;
}

export interface PostSaveResult {
  readonly transactionId: number;
  readonly targetMonth: string;
}

export type ValidationResult<T> = { readonly ok: true; readonly value: T } | { readonly ok: false; readonly reason: string };

export function createTransactionForm(now: Date): CreateTransactionForm {
  return {
    type: "EXPENSE",
    amount: "",
    name: "",
    categoryId: null,
    selectedTagIds: [],
    businessTime: currentBeijingBusinessTime(now),
    remark: "",
  };
}

export function switchTransactionType(form: CreateTransactionForm, nextType: TransactionType): CreateTransactionForm {
  if (form.type === nextType) {
    return { ...form, selectedTagIds: [...form.selectedTagIds] };
  }
  return {
    type: nextType,
    amount: form.amount,
    name: form.name,
    categoryId: null,
    selectedTagIds: [],
    businessTime: form.businessTime,
    remark: form.remark,
  };
}

export function validateTransactionAmount(raw: string): ValidationResult<string> {
  const amount = raw.trim();
  if (amount.length === 0) {
    return { ok: false, reason: "amount-required" };
  }
  if (!AMOUNT_PATTERN.test(amount)) {
    return { ok: false, reason: "amount-invalid" };
  }
  if (ZERO_AMOUNT_PATTERN.test(amount)) {
    return { ok: false, reason: "amount-zero" };
  }
  return { ok: true, value: amount };
}

export function normalizeOptionalText(raw: string): string | null {
  const value = raw.trim();
  return value.length === 0 ? null : value;
}

export function buildCreateTransactionSnapshot(
  form: CreateTransactionForm,
  accountId: number,
): ValidationResult<CreateTransactionSnapshot> {
  if (form.type !== "INCOME" && form.type !== "EXPENSE") {
    return { ok: false, reason: "type-invalid" };
  }
  if (!Number.isSafeInteger(accountId) || accountId <= 0) {
    return { ok: false, reason: "account-invalid" };
  }
  const amount = validateTransactionAmount(form.amount);
  if (!amount.ok) {
    return amount;
  }
  if (!validateBusinessTime(form.businessTime)) {
    return { ok: false, reason: "business-time-invalid" };
  }
  if (form.categoryId !== null && (!Number.isSafeInteger(form.categoryId) || form.categoryId <= 0)) {
    return { ok: false, reason: "category-invalid" };
  }
  const tagIds: number[] = [];
  for (const tagId of form.selectedTagIds) {
    if (!Number.isSafeInteger(tagId) || tagId <= 0) {
      return { ok: false, reason: "tag-invalid" };
    }
    if (!tagIds.includes(tagId)) {
      tagIds.push(tagId);
    }
  }
  tagIds.sort((left, right) => left - right);
  return {
    ok: true,
    value: {
      accountId,
      type: form.type,
      amount: amount.value,
      name: normalizeOptionalText(form.name),
      categoryId: form.categoryId,
      tagIds,
      businessTime: form.businessTime,
      remark: normalizeOptionalText(form.remark),
    },
  };
}

export function createTransactionRequestBody(snapshot: CreateTransactionSnapshot): CreateTransactionRequestBody {
  return {
    type: snapshot.type,
    amount: snapshot.amount,
    ...(snapshot.name === null ? {} : { name: snapshot.name }),
    ...(snapshot.categoryId === null ? {} : { categoryId: snapshot.categoryId }),
    tagIds: [...snapshot.tagIds],
    businessTime: snapshot.businessTime,
    ...(snapshot.remark === null ? {} : { remark: snapshot.remark }),
  };
}

export function postSaveResult(transaction: TransactionView): PostSaveResult | null {
  const targetMonth = businessMonth(transaction.businessTime);
  if (targetMonth === null) {
    return null;
  }
  return { transactionId: transaction.id, targetMonth };
}
