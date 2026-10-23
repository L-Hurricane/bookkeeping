import { canonicalMoney } from "../balance-adjustment/money";

export function positiveTransferAmount(raw: string): string | null {
  const canonical = canonicalMoney(raw);
  if (canonical === null || canonical === "0.00" || canonical.startsWith("-")) {
    return null;
  }
  return canonical;
}

export function isPositiveTransferAmount(value: string): boolean {
  return positiveTransferAmount(value) === value;
}
