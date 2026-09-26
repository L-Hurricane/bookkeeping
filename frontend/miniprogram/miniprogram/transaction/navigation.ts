import { validateBusinessTime } from "./business-time";

export interface CreatePageContext {
  readonly accountId: number;
  readonly accountCreatedTime: string;
}

export function canOpenCreateTransaction(phase: string, loggingOut: boolean): boolean {
  return !loggingOut && (phase === "ledger-loading" || phase === "ready" || phase === "ledger-error");
}

export function transactionCreatePath(accountId: number, accountCreatedTime: string): string {
  const query = `accountId=${accountId}&accountCreatedTime=${encodeURIComponent(accountCreatedTime)}`;
  return `/pages/transaction-create/index?${query}`;
}

export function parseCreatePageContext(query: Record<string, string | undefined>): CreatePageContext | null {
  const rawId = query.accountId;
  if (rawId === undefined || !/^[1-9][0-9]*$/.test(rawId)) {
    return null;
  }
  const accountId = Number(rawId);
  const rawCreatedTime = query.accountCreatedTime;
  if (!Number.isSafeInteger(accountId) || rawCreatedTime === undefined) {
    return null;
  }
  const accountCreatedTime = parseBusinessTimeQueryValue(rawCreatedTime);
  if (accountCreatedTime === null) {
    return null;
  }
  return { accountId, accountCreatedTime };
}

const MONTH_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/;

export interface DetailPageContext {
  readonly accountId: number;
  readonly transactionId: number;
  readonly accountCreatedTime: string;
  readonly originMonth: string;
}

export function canOpenTransactionDetail(kind: string, loggingOut: boolean): boolean {
  return kind === "transaction" && !loggingOut;
}

export function transactionDetailPath(
  accountId: number,
  transactionId: number,
  accountCreatedTime: string,
  originMonth: string,
): string {
  const query = [
    `accountId=${accountId}`,
    `transactionId=${transactionId}`,
    `accountCreatedTime=${encodeURIComponent(accountCreatedTime)}`,
    `originMonth=${originMonth}`,
  ].join("&");
  return `/pages/transaction-detail/index?${query}`;
}

export function parseDetailPageContext(query: Record<string, string | undefined>): DetailPageContext | null {
  const accountId = parsePositiveId(query.accountId);
  const transactionId = parsePositiveId(query.transactionId);
  const rawCreatedTime = query.accountCreatedTime;
  const originMonth = query.originMonth;
  if (accountId === null || transactionId === null || rawCreatedTime === undefined || originMonth === undefined || !MONTH_PATTERN.test(originMonth)) {
    return null;
  }
  const accountCreatedTime = parseBusinessTimeQueryValue(rawCreatedTime);
  if (accountCreatedTime === null) {
    return null;
  }
  return { accountId, transactionId, accountCreatedTime, originMonth };
}

function parsePositiveId(raw: string | undefined): number | null {
  if (raw === undefined || !/^[1-9][0-9]*$/.test(raw)) {
    return null;
  }
  const id = Number(raw);
  return Number.isSafeInteger(id) ? id : null;
}

export function parseBusinessTimeQueryValue(raw: string): string | null {
  if (validateBusinessTime(raw)) {
    return raw;
  }
  try {
    const decoded = decodeURIComponent(raw);
    return validateBusinessTime(decoded) ? decoded : null;
  } catch {
    return null;
  }
}
