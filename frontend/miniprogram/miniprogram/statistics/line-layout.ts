/** Pixel positions for server-provided net-worth points. Does not create or alter amounts. */
export function lineYs(amounts: readonly string[], height: number): number[] {
  const values = amounts.map(cents);
  if (values.length === 0 || height <= 0) return [];
  let min = values[0] ?? 0;
  let max = min;
  for (const value of values) {
    if (value < min) min = value;
    if (value > max) max = value;
  }
  const span = max - min;
  const pad = 12;
  const inner = Math.max(height - pad * 2, 1);
  return values.map((value) => {
    if (span === 0) return height / 2;
    return pad + ((max - value) / span) * inner;
  });
}

function cents(amount: string): number {
  const negative = amount.startsWith("-");
  const digits = amount.replace("-", "").split(".");
  const whole = Number(digits[0] ?? "0");
  const fraction = Number((digits[1] ?? "00").padEnd(2, "0").slice(0, 2));
  const value = whole * 100 + fraction;
  return negative ? -value : value;
}
