import { formatMonthLabel } from "./month";

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/;
const DATE = /^(\d{4})-(\d{2})-(\d{2})$/;
const DAY_MS = 86_400_000;

export type AccountLedgerFilter =
  | { readonly mode: "all" }
  | { readonly mode: "month"; readonly month: string }
  | { readonly mode: "range"; readonly from: string; readonly to: string };

export function accountLedgerFilterKey(filter: AccountLedgerFilter): string {
  if (filter.mode === "month") return `month:${filter.month}`;
  if (filter.mode === "range") return `range:${filter.from}:${filter.to}`;
  return "all";
}

export function accountLedgerFilterLabel(filter: AccountLedgerFilter): string {
  if (filter.mode === "month") return formatMonthLabel(filter.month);
  if (filter.mode === "range") return `${filter.from} 至 ${filter.to}`;
  return "全部时间";
}

export function accountLedgerEmptyText(filter: AccountLedgerFilter): string {
  if (filter.mode === "month") return "本月暂无记录";
  if (filter.mode === "range") return "该范围暂无记录";
  return "暂无记录";
}

export function isUsableDateRange(from: string, to: string): boolean {
  if (!isCalendarDate(from) || !isCalendarDate(to) || to < from) return false;
  const start = Date.parse(`${from}T00:00:00Z`);
  const end = Date.parse(`${to}T00:00:00Z`);
  return (end - start) / DAY_MS <= 3660;
}

function isCalendarDate(value: string): boolean {
  const match = DATE.exec(value);
  if (match === null) return false;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const parsed = new Date(Date.UTC(year, month - 1, day));
  return parsed.getUTCFullYear() === year && parsed.getUTCMonth() + 1 === month && parsed.getUTCDate() === day;
}

export function appendLedgerPage<T extends { readonly kind: string; readonly id: number }>(
  current: readonly T[],
  incoming: readonly T[],
): T[] {
  const seen = new Set(current.map((item) => `${item.kind}:${item.id}`));
  const next = current.slice();
  for (const item of incoming) {
    const key = `${item.kind}:${item.id}`;
    if (seen.has(key)) continue;
    seen.add(key);
    next.push(item);
  }
  return next;
}

export function isMonth(value: string): boolean {
  return MONTH.test(value);
}
