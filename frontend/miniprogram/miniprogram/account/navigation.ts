import { validateBusinessTime } from "../transaction/business-time";

export function accountListPath(): string {
  return "/pages/account-list/index";
}

/** After a successful archive, replace the stack so the archived account page cannot be reopened from Back. */
export function accountListAfterArchivePath(accountId: number): string {
  return `/pages/account-list/index?notice=archived&accountId=${accountId}`;
}

export function parseAccountListNotice(
  query: Record<string, string | undefined>,
): { readonly accountId: number } | null {
  if (query.notice !== "archived") {
    return null;
  }
  const accountId = parseAccountIdQuery(query.accountId);
  if (accountId === null) {
    return null;
  }
  return { accountId };
}

/** Success is shown only when the refreshed active list no longer contains the archived account. */
export function archiveArrivalFeedback(
  accounts: readonly { readonly id: number }[],
  accountId: number,
): "success" | "withhold" {
  for (const account of accounts) {
    if (account.id === accountId) {
      return "withhold";
    }
  }
  return "success";
}

/** A visible list stays on screen while a later visit refreshes it. The first visit still blocks. */
export function accountListReloadMode(phase: "loading" | "ready" | "error"): "blocking" | "background" {
  return phase === "ready" ? "background" : "blocking";
}

export function accountCreatePath(): string {
  return "/pages/account-create/index";
}

export function accountArchivePath(): string {
  return "/pages/account-archive/index";
}

export function accountHomePath(accountId: number): string {
  return `/pages/account-home/index?accountId=${accountId}`;
}

export function accountSettingsPath(accountId: number): string {
  return `/pages/account-settings/index?accountId=${accountId}`;
}

export function accountPermanentDeletionPath(accountId: number): string {
  return `/pages/account-permanent-deletion/index?accountId=${accountId}`;
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
