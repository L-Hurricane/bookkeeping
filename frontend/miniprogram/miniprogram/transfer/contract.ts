import { isPositiveTransferAmount } from "./money";

const BUSINESS_TIME_PATTERN = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})\+08:00$/;

export interface TransferView {
  readonly id: number;
  readonly fromAccountId: number;
  readonly toAccountId: number;
  readonly amount: string;
  readonly fromBusinessTime: string;
  readonly toBusinessTime: string;
  readonly firstSaveOrder: number;
}

export interface TransferRequestBody {
  readonly fromAccountId: number;
  readonly toAccountId: number;
  readonly amount: string;
  readonly fromBusinessTime: string;
  readonly toBusinessTime: string;
}

export function parseTransferView(value: unknown): TransferView | null {
  if (!isRecord(value)) {
    return null;
  }
  const id = value.id;
  const fromAccountId = value.fromAccountId;
  const toAccountId = value.toAccountId;
  const amount = value.amount;
  const fromBusinessTime = value.fromBusinessTime;
  const toBusinessTime = value.toBusinessTime;
  const firstSaveOrder = value.firstSaveOrder;
  if (
    !isPositiveId(id) ||
    !isPositiveId(fromAccountId) ||
    !isPositiveId(toAccountId) ||
    typeof amount !== "string" ||
    !isPositiveTransferAmount(amount) ||
    typeof fromBusinessTime !== "string" ||
    !isBusinessTime(fromBusinessTime) ||
    typeof toBusinessTime !== "string" ||
    !isBusinessTime(toBusinessTime) ||
    typeof firstSaveOrder !== "number" ||
    !Number.isSafeInteger(firstSaveOrder) ||
    firstSaveOrder <= 0
  ) {
    return null;
  }
  return {
    id,
    fromAccountId,
    toAccountId,
    amount,
    fromBusinessTime,
    toBusinessTime,
    firstSaveOrder,
  };
}

function isBusinessTime(value: string): boolean {
  return BUSINESS_TIME_PATTERN.test(value);
}

function isPositiveId(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}
