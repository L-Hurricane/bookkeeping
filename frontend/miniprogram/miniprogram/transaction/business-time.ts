import { beijingWallClock } from "../ledger/month";

const BUSINESS_TIME_PATTERN = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})\+08:00$/;

export function currentBeijingBusinessTime(now: Date): string {
  const clock = beijingWallClock(now);
  return `${pad(clock.year, 4)}-${pad(clock.month, 2)}-${pad(clock.day, 2)}T${pad(clock.hour, 2)}:${pad(clock.minute, 2)}:${pad(clock.second, 2)}+08:00`;
}

export function businessMonth(businessTime: string): string | null {
  if (!validateBusinessTime(businessTime)) {
    return null;
  }
  return businessTime.slice(0, 7);
}

export function validateBusinessTime(value: string): boolean {
  const match = BUSINESS_TIME_PATTERN.exec(value);
  if (match === null) {
    return false;
  }
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const hour = Number(match[4]);
  const minute = Number(match[5]);
  const second = Number(match[6]);
  if (month < 1 || month > 12 || hour > 23 || minute > 59 || second > 59) {
    return false;
  }
  return day >= 1 && day <= daysInMonth(year, month);
}

export function businessTimeDisplay(value: string): string | null {
  return validateBusinessTime(value) ? `${value.slice(0, 10)} ${value.slice(11, 19)}` : null;
}

export interface BusinessTimeDraft {
  readonly date: string;
  readonly time: string;
}

export function businessTimeDraft(value: string): BusinessTimeDraft | null {
  if (!validateBusinessTime(value)) {
    return null;
  }
  return { date: value.slice(0, 10), time: value.slice(11, 19) };
}

export function businessTimeFromParts(date: string, time: string): string | null {
  const value = `${date}T${time}+08:00`;
  return validateBusinessTime(value) ? value : null;
}

export function currentBeijingDate(now: Date): string {
  const clock = beijingWallClock(now);
  return formatDate(clock.year, clock.month, clock.day);
}

export function previousBeijingDate(now: Date): string {
  const clock = beijingWallClock(now);
  if (clock.day > 1) {
    return formatDate(clock.year, clock.month, clock.day - 1);
  }
  if (clock.month === 1) {
    return formatDate(clock.year - 1, 12, 31);
  }
  const month = clock.month - 1;
  return formatDate(clock.year, month, daysInMonth(clock.year, month));
}

export function applyNow(now: Date): BusinessTimeDraft {
  const draft = businessTimeDraft(currentBeijingBusinessTime(now));
  if (draft === null) {
    return { date: "", time: "" };
  }
  return draft;
}

export function applyToday(draft: BusinessTimeDraft, now: Date): BusinessTimeDraft {
  return { date: currentBeijingDate(now), time: draft.time };
}

export function applyYesterday(draft: BusinessTimeDraft, now: Date): BusinessTimeDraft {
  return { date: previousBeijingDate(now), time: draft.time };
}

export function applyBusinessTimeDraft(
  draft: BusinessTimeDraft,
  accountCreatedTime: string,
): { readonly ok: true; readonly businessTime: string } | { readonly ok: false; readonly message: string } {
  const businessTime = businessTimeFromParts(draft.date, draft.time);
  if (businessTime === null) {
    return { ok: false, message: "请输入有效的日期和时间" };
  }
  if (!isBusinessTimeAllowed(businessTime, accountCreatedTime)) {
    return { ok: false, message: "交易时间不得早于账户创建时间" };
  }
  return { ok: true, businessTime };
}

export function resolveBusinessTimeEdit(
  action: "confirm" | "cancel",
  current: string,
  draft: BusinessTimeDraft,
  accountCreatedTime: string,
): { readonly businessTime: string; readonly error: string; readonly open: boolean } {
  if (action === "cancel") {
    return { businessTime: current, error: "", open: false };
  }
  const applied = applyBusinessTimeDraft(draft, accountCreatedTime);
  if (!applied.ok) {
    return { businessTime: current, error: applied.message, open: true };
  }
  return { businessTime: applied.businessTime, error: "", open: false };
}

export function isFutureBusinessTime(value: string, now: Date): boolean {
  const current = currentBeijingBusinessTime(now);
  return validateBusinessTime(value) && value > current;
}

export function isBusinessTimeAllowed(businessTime: string, accountCreatedTime: string): boolean {
  if (!validateBusinessTime(businessTime) || !validateBusinessTime(accountCreatedTime)) {
    return false;
  }
  return businessTime >= accountCreatedTime;
}

function formatDate(year: number, month: number, day: number): string {
  return `${pad(year, 4)}-${pad(month, 2)}-${pad(day, 2)}`;
}

function daysInMonth(year: number, month: number): number {
  if (month === 2) {
    return year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0) ? 29 : 28;
  }
  if (month === 4 || month === 6 || month === 9 || month === 11) {
    return 30;
  }
  return 31;
}

function pad(value: number, width: number): string {
  return String(value).padStart(width, "0");
}
