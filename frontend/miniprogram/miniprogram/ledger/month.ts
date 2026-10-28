const MONTH_PATTERN = /^(\d{4})-(0[1-9]|1[0-2])$/;
const BEIJING_OFFSET_MS = 8 * 60 * 60 * 1000;

export function beijingWallClock(now: Date): {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
  second: number;
} {
  const beijing = new Date(now.getTime() + BEIJING_OFFSET_MS);
  return {
    year: beijing.getUTCFullYear(),
    month: beijing.getUTCMonth() + 1,
    day: beijing.getUTCDate(),
    hour: beijing.getUTCHours(),
    minute: beijing.getUTCMinutes(),
    second: beijing.getUTCSeconds(),
  };
}

export function currentBeijingMonth(now: Date): string {
  const beijing = beijingWallClock(now);
  return formatMonth(beijing.year, beijing.month);
}

export function previousMonth(month: string): string {
  const parsed = parseMonth(month);
  if (parsed.month === 1) {
    return formatMonth(parsed.year - 1, 12);
  }
  return formatMonth(parsed.year, parsed.month - 1);
}

export function nextMonth(month: string): string {
  const parsed = parseMonth(month);
  if (parsed.month === 12) {
    return formatMonth(parsed.year + 1, 1);
  }
  return formatMonth(parsed.year, parsed.month + 1);
}

export function beijingDate(now: Date): string {
  const beijing = beijingWallClock(now);
  return `${beijing.year}-${String(beijing.month).padStart(2, "0")}-${String(beijing.day).padStart(2, "0")}`;
}

export function monthLastDate(month: string): string {
  const parsed = parseMonth(month);
  const last = new Date(Date.UTC(parsed.year, parsed.month, 0)).getUTCDate();
  return `${month}-${String(last).padStart(2, "0")}`;
}

export function formatMonthLabel(month: string): string {
  const parsed = parseMonth(month);
  return `${parsed.year}年${parsed.month}月`;
}

function parseMonth(month: string): { year: number; month: number } {
  const match = MONTH_PATTERN.exec(month);
  if (match === null) {
    throw new Error("月份必须为 YYYY-MM");
  }
  return { year: Number(match[1]), month: Number(match[2]) };
}

function formatMonth(year: number, month: number): string {
  return `${year}-${String(month).padStart(2, "0")}`;
}
