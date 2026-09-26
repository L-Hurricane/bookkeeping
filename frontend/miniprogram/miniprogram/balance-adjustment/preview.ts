import { formatMoneyLabel } from "../ledger/ledger";
import { canonicalMoney, signedDifference, subtractMoneyStrings } from "./money";

export interface AdjustmentPreview {
  readonly targetBalance: string;
  readonly adjustmentAmount: string;
  readonly difference: string;
  readonly zero: boolean;
  readonly caption: string;
  readonly amountLabel: string;
  readonly afterLabel: string;
}

export function previewAdjustment(
  currentBalance: string,
  targetRaw: string,
): { readonly ok: true; readonly preview: AdjustmentPreview } | { readonly ok: false; readonly reason: string } {
  const target = targetRaw.trim();
  if (target.length === 0) {
    return { ok: false, reason: "target-required" };
  }
  const targetBalance = canonicalMoney(target);
  if (targetBalance === null) {
    return { ok: false, reason: "target-invalid" };
  }
  const adjustmentAmount = subtractMoneyStrings(targetBalance, currentBalance);
  if (adjustmentAmount === null || canonicalMoney(currentBalance) === null) {
    return { ok: false, reason: "current-invalid" };
  }
  const zero = adjustmentAmount === "0.00";
  const absolute = adjustmentAmount.startsWith("-") ? adjustmentAmount.slice(1) : adjustmentAmount;
  return {
    ok: true,
    preview: {
      targetBalance,
      adjustmentAmount,
      difference: signedDifference(adjustmentAmount),
      zero,
      caption: zero ? "预计调整金额" : adjustmentAmount.startsWith("-") ? "预计减少" : "预计增加",
      amountLabel: formatMoneyLabel(absolute),
      afterLabel: formatMoneyLabel(targetBalance),
    },
  };
}

export const ZERO_ADJUSTMENT_MESSAGE = "当前余额已经是目标余额，无需调整。";
export const FUTURE_ADJUSTMENT_HINT = "未来日期的余额调整保存后仍会立即影响当前余额；流水归入所选自然月。";
