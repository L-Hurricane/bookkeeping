/** UI Freeze: amount color follows mathematical sign, not income/expense type. */
export type MoneyTone = "positive" | "negative" | "zero";

const ZERO_MONEY_PATTERN = /^[+-]?0+(?:\.0+)?$/;

export function moneyTone(amount: string): MoneyTone {
  const trimmed = amount.trim();
  if (trimmed.length === 0 || ZERO_MONEY_PATTERN.test(trimmed)) {
    return "zero";
  }
  if (trimmed.startsWith("-")) {
    return "negative";
  }
  return "positive";
}

export function moneyToneClass(amount: string): string {
  const tone = moneyTone(amount);
  if (tone === "positive") {
    return "money-positive";
  }
  if (tone === "negative") {
    return "money-negative";
  }
  return "money-zero";
}
