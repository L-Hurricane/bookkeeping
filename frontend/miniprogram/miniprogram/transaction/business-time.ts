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

export function normalizeBusinessDateInput(raw: string): string | null {
  const trimmed = raw.trim();
  if (trimmed.length === 0) {
    return null;
  }
  const parts = trimmed.replace(/\//g, "-").split("-");
  if (parts.length !== 3) {
    return null;
  }
  if (!parts.every((part) => /^\d{1,4}$/.test(part))) {
    return null;
  }
  const year = Number(parts[0]);
  const month = Number(parts[1]);
  const day = Number(parts[2]);
  if (!Number.isInteger(year) || !Number.isInteger(month) || !Number.isInteger(day)) {
    return null;
  }
  if (year < 1 || month < 1 || month > 12 || day < 1 || day > 31) {
    return null;
  }
  const date = formatDate(year, month, day);
  return validateBusinessTime(`${date}T00:00:00+08:00`) ? date : null;
}

export function normalizeBusinessTimeInput(raw: string): string | null {
  const trimmed = raw.trim();
  if (trimmed.length === 0) {
    return null;
  }
  if (/^\d{6}$/.test(trimmed)) {
    return formatClock(
      Number(trimmed.slice(0, 2)),
      Number(trimmed.slice(2, 4)),
      Number(trimmed.slice(4, 6)),
    );
  }
  if (!trimmed.includes(":")) {
    return null;
  }
  const parts = trimmed.split(":");
  if (parts.length < 2 || parts.length > 3) {
    return null;
  }
  if (!parts.every((part) => /^\d{1,2}$/.test(part))) {
    return null;
  }
  const hour = Number(parts[0]);
  const minute = Number(parts[1]);
  const second = parts.length === 3 ? Number(parts[2]) : 0;
  return formatClock(hour, minute, second);
}

export function normalizeDateOnBlur(raw: string): string {
  return normalizeBusinessDateInput(raw) ?? raw;
}

export function normalizeTimeOnBlur(raw: string): string {
  return normalizeBusinessTimeInput(raw) ?? raw;
}

export function businessTimePickerMinute(time: string): string {
  const normalized = normalizeBusinessTimeInput(time);
  if (normalized !== null) {
    return normalized.slice(0, 5);
  }
  const match = /^(\d{1,2}):(\d{1,2})/.exec(time.trim());
  if (match === null) {
    return "00:00";
  }
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  if (hour > 23 || minute > 59) {
    return "00:00";
  }
  return `${pad(hour, 2)}:${pad(minute, 2)}`;
}

export function applyDatePickerValue(pickerDate: string, draft: BusinessTimeDraft): BusinessTimeDraft {
  const date = normalizeBusinessDateInput(pickerDate) ?? pickerDate;
  return { date, time: draft.time };
}

export function applyTimePickerValue(pickerTime: string, draft: BusinessTimeDraft): BusinessTimeDraft {
  const match = /^(\d{1,2}):(\d{1,2})$/.exec(pickerTime.trim());
  if (match === null) {
    return draft;
  }
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  const clock = formatClock(hour, minute, 0);
  if (clock === null) {
    return draft;
  }
  return { date: draft.date, time: clock };
}

export function normalizeBusinessTimeDraft(draft: BusinessTimeDraft): BusinessTimeDraft | null {
  const date = normalizeBusinessDateInput(draft.date);
  const time = normalizeBusinessTimeInput(draft.time);
  if (date === null || time === null) {
    return null;
  }
  return { date, time };
}

export function timeEditorFields(draft: BusinessTimeDraft): {
  readonly timeDate: string;
  readonly timeClock: string;
  readonly timePickerMinute: string;
} {
  return {
    timeDate: draft.date,
    timeClock: draft.time,
    timePickerMinute: businessTimePickerMinute(draft.time),
  };
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
  const normalized = normalizeBusinessTimeDraft(draft);
  if (normalized === null) {
    return { ok: false, message: "请输入有效的日期和时间" };
  }
  const businessTime = businessTimeFromParts(normalized.date, normalized.time);
  if (businessTime === null) {
    return { ok: false, message: "请输入有效的日期和时间" };
  }
  if (!validateBusinessTime(businessTime)) {
    return { ok: false, message: "交易时间格式不正确" };
  }
  void accountCreatedTime;
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

/**
 * v1.0 / BAL-05 / TX-06: business time may be earlier than account creation.
 * accountCreatedTime remains in the signature for call-site compatibility; only format validity is required.
 */
export function isBusinessTimeAllowed(businessTime: string, accountCreatedTime: string): boolean {
  if (!validateBusinessTime(businessTime)) {
    return false;
  }
  if (accountCreatedTime.length > 0 && !validateBusinessTime(accountCreatedTime)) {
    return false;
  }
  return true;
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

function formatClock(hour: number, minute: number, second: number): string | null {
  if (!Number.isInteger(hour) || hour < 0 || hour > 23) {
    return null;
  }
  if (!Number.isInteger(minute) || minute < 0 || minute > 59) {
    return null;
  }
  if (!Number.isInteger(second) || second < 0 || second > 59) {
    return null;
  }
  return `${pad(hour, 2)}:${pad(minute, 2)}:${pad(second, 2)}`;
}
