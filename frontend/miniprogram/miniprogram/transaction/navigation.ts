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
