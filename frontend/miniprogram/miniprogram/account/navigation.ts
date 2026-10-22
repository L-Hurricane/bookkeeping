import { validateBusinessTime } from "../transaction/business-time";

export function accountListPath(): string {
  return "/pages/account-list/index";
}

export function accountCreatePath(): string {
  return "/pages/account-create/index";
}

export function accountHomePath(accountId: number): string {
  return `/pages/account-home/index?accountId=${accountId}`;
}

export function accountSettingsPath(accountId: number): string {
  return `/pages/account-settings/index?accountId=${accountId}`;
}

export function parseAccountIdQuery(raw: string | undefined): number | null {
  if (raw === undefined || raw.length === 0) {
    return null;
  }
  const id = Number(raw);
  if (!Number.isSafeInteger(id) || id <= 0) {
    return null;
  }
  return id;
}

export function parseAccountHomeQuery(query: Record<string, string | undefined>): { readonly accountId: number } | null {
  const accountId = parseAccountIdQuery(query.accountId);
  if (accountId === null) {
    return null;
  }
  return { accountId };
}

export function parseAccountSettingsQuery(
  query: Record<string, string | undefined>,
): { readonly accountId: number } | null {
  return parseAccountHomeQuery(query);
}

/** Kept for callers that still encode createdTime in transaction routes. */
export function encodeAccountCreatedTime(createdTime: string): string {
  return encodeURIComponent(createdTime);
}

export function isUsableAccountCreatedTime(createdTime: string): boolean {
  return validateBusinessTime(createdTime);
}
