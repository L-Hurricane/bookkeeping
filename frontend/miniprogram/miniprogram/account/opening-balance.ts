const OPENING_BALANCE_PATTERN = /^[+-]?[0-9]+(\.[0-9]{1,2})?$/;

export function normalizeOpeningBalance(raw: string): string | null {
  const value = raw.trim();
  if (value === "") {
    return "0";
  }
  if (!OPENING_BALANCE_PATTERN.test(value)) {
    return null;
  }
  return value;
}
