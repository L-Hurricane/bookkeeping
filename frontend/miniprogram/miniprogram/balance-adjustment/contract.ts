import { validateBusinessTime } from "../transaction/business-time";
import { canonicalMoney, moneyEqual, subtractMoneyStrings } from "./money";

export interface AdjustmentRecord {
  readonly id: number;
  readonly adjustmentAmount: string;
  readonly businessTime: string;
  readonly remark: string | null;
}

export type AdjustmentCreateResult =
  | {
      readonly created: true;
      readonly currentBalanceBefore: string;
      readonly targetBalance: string;
      readonly adjustment: AdjustmentRecord;
      readonly currentBalanceAfter: string;
    }
  | {
      readonly created: false;
      readonly reason: "NO_ADJUSTMENT_NEEDED";
      readonly currentBalance: string;
      readonly targetBalance: string;
    };

export function parseAdjustmentCreateResult(value: unknown, snapshotTarget: string): AdjustmentCreateResult | null {
  if (!isRecord(value) || typeof value.created !== "boolean") {
    return null;
  }
  const targetBalance = canonicalMoney(readString(value.targetBalance) ?? "");
  if (targetBalance === null || !moneyEqual(targetBalance, snapshotTarget)) {
    return null;
  }
  if (value.created) {
    const before = canonicalMoney(readString(value.currentBalanceBefore) ?? "");
    const after = canonicalMoney(readString(value.currentBalanceAfter) ?? "");
    const adjustment = parseAdjustmentRecord(value.adjustment);
    if (before === null || after === null || adjustment === null || moneyEqual(adjustment.adjustmentAmount, "0.00")) {
      return null;
    }
    const expectedAmount = subtractMoneyStrings(targetBalance, before);
    if (!moneyEqual(after, targetBalance) || expectedAmount === null || !moneyEqual(adjustment.adjustmentAmount, expectedAmount)) {
      return null;
    }
    return { created: true, currentBalanceBefore: before, targetBalance, adjustment, currentBalanceAfter: after };
  }
  if (value.reason !== "NO_ADJUSTMENT_NEEDED" || (value.adjustment !== undefined && value.adjustment !== null)) {
    return null;
  }
  const currentBalance = canonicalMoney(readString(value.currentBalance) ?? "");
  if (currentBalance === null || !moneyEqual(currentBalance, targetBalance)) {
    return null;
  }
  return { created: false, reason: "NO_ADJUSTMENT_NEEDED", currentBalance, targetBalance };
}

export function parseAdjustmentDetail(value: unknown, adjustmentId: number): AdjustmentRecord | null {
  const adjustment = parseAdjustmentRecord(value);
  if (adjustment === null || adjustment.id !== adjustmentId || moneyEqual(adjustment.adjustmentAmount, "0.00")) {
    return null;
  }
  return adjustment;
}

function parseAdjustmentRecord(value: unknown): AdjustmentRecord | null {
  if (!isRecord(value) || !isPositiveId(value.id)) {
    return null;
  }
  const adjustmentAmount = canonicalMoney(readString(value.adjustmentAmount) ?? "");
  const businessTime = readString(value.businessTime);
  if (adjustmentAmount === null || businessTime === null || !validateBusinessTime(businessTime)) {
    return null;
  }
  if (!("remark" in value) || value.remark === null) {
    return { id: value.id, adjustmentAmount, businessTime, remark: null };
  }
  if (typeof value.remark !== "string") {
    return null;
  }
  return { id: value.id, adjustmentAmount, businessTime, remark: value.remark };
}

function readString(value: unknown): string | null {
  return typeof value === "string" ? value : null;
}

function isPositiveId(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}
