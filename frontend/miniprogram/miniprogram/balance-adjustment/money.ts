const MONEY_INPUT_PATTERN = /^[+-]?[0-9]+(\.[0-9]{1,2})?$/;

export function canonicalMoney(raw: string): string | null {
  const cents = moneyToCents(raw);
  return cents === null ? null : formatCents(cents);
}

export function moneyEqual(left: string, right: string): boolean {
  const first = moneyToCents(left);
  const second = moneyToCents(right);
  return first !== null && second !== null && first === second;
}

export function isCanonicalMoney(value: string): boolean {
  return canonicalMoney(value) === value;
}

export function subtractMoneyStrings(left: string, right: string): string | null {
  const first = moneyToCents(left);
  const second = moneyToCents(right);
  if (first === null || second === null) {
    return null;
  }
  return formatCents(first - second);
}

export function signedDifference(canonical: string): string {
  if (canonical === "0.00" || canonical.startsWith("-")) {
    return canonical;
  }
  return `+${canonical}`;
}

export function formatAdjustmentAmount(canonical: string): string {
  if (canonical.startsWith("-")) {
    return `-¥${canonical.slice(1)}`;
  }
  return `¥${canonical}`;
}

function moneyToCents(raw: string): bigint | null {
  const value = raw.trim();
  if (!MONEY_INPUT_PATTERN.test(value)) {
    return null;
  }
  const negative = value.startsWith("-");
  const [whole, fraction = ""] = value.replace(/^[+-]/, "").split(".");
  const cents = BigInt(`${whole}${fraction.padEnd(2, "0")}`);
  if (cents === 0n) {
    return 0n;
  }
  return negative ? -cents : cents;
}

function formatCents(cents: bigint): string {
  const negative = cents < 0n;
  const text = (negative ? -cents : cents).toString().padStart(3, "0");
  const whole = text.slice(0, -2).replace(/^0+(?=\d)/, "");
  return `${negative ? "-" : ""}${whole}.${text.slice(-2)}`;
}
