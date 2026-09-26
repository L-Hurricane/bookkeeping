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

export function isBusinessTimeAllowed(businessTime: string, accountCreatedTime: string): boolean {
  if (!validateBusinessTime(businessTime) || !validateBusinessTime(accountCreatedTime)) {
    return false;
  }
  return businessTime >= accountCreatedTime;
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
