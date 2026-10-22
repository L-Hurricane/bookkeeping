export type AccountKind = "NORMAL" | "CREDIT";

export interface Account {
  readonly id: number;
  readonly name: string;
  readonly kind: AccountKind;
  readonly currency: string;
  readonly openingBalance: string;
  readonly currentBalance: string;
  readonly createdTime: string;
}

export type AccountStartupRoute =
  | { readonly destination: "init" }
  | { readonly destination: "list"; readonly accounts: readonly Account[] }
  | { readonly destination: "error"; readonly message: string };

export const ACCOUNT_CONTRACT_MESSAGE = "账户数据不符合约定";

const MONEY_RESPONSE_PATTERN = /^-?[0-9]+\.[0-9]{2}$/;

export function parseAccount(value: unknown): Account | null {
  if (!isRecord(value)) {
    return null;
  }
  const id = value.id;
  const name = value.name;
  const kind = value.kind;
  const currency = value.currency;
  const openingBalance = value.openingBalance;
  const currentBalance = value.currentBalance;
  const createdTime = value.createdTime;
  if (typeof id !== "number" || !Number.isSafeInteger(id) || id <= 0) {
    return null;
  }
  if (!isNonEmptyString(name) || !isNonEmptyString(currency) || !isAccountKind(kind)) {
    return null;
  }
  if (!isMoneyResponse(openingBalance) || !isMoneyResponse(currentBalance) || !isCreatedTime(createdTime)) {
    return null;
  }
  return { id, name, kind, currency, openingBalance, currentBalance, createdTime };
}

export function parseAccountList(value: unknown): Account[] | null {
  if (!isRecord(value) || !Array.isArray(value.items)) {
    return null;
  }
  const accounts: Account[] = [];
  for (const item of value.items) {
    const account = parseAccount(item);
    if (account === null) {
      return null;
    }
    accounts.push(account);
  }
  return accounts;
}

export function routeAfterAccountQuery(items: Account[]): AccountStartupRoute {
  if (items.length === 0) {
    return { destination: "init" };
  }
  return { destination: "list", accounts: items };
}

export function formatCurrentBalance(account: Account): string {
  if (account.currency === "CNY") {
    return `¥${account.currentBalance}`;
  }
  return `${account.currentBalance} ${account.currency}`;
}

export function accountKindLabel(kind: AccountKind): string {
  return kind === "CREDIT" ? "信用账户" : "普通账户";
}

/** Ordinary accounts warn on negative balance; credit accounts treat negatives as debt without that warning. */
export function shouldWarnNegativeBalance(account: Account): boolean {
  return account.kind === "NORMAL" && isNegativeMoney(account.currentBalance);
}

export function findAccount(accounts: readonly Account[], accountId: number): Account | null {
  for (const account of accounts) {
    if (account.id === accountId) {
      return account;
    }
  }
  return null;
}

/**
 * TX-04 / S1-T05: with exactly one usable account, default-select it.
 * With multiple accounts, keep the preferred id when still present; otherwise leave unset for the UI to choose.
 */
export function defaultSelectableAccountId(
  accounts: readonly Account[],
  preferredAccountId: number | null,
): number | null {
  if (accounts.length === 0) {
    return null;
  }
  if (accounts.length === 1) {
    return accounts[0]?.id ?? null;
  }
  if (preferredAccountId !== null && findAccount(accounts, preferredAccountId) !== null) {
    return preferredAccountId;
  }
  return null;
}

function isAccountKind(value: unknown): value is AccountKind {
  return value === "NORMAL" || value === "CREDIT";
}

function isNegativeMoney(value: string): boolean {
  return value.startsWith("-") && !/^[-]?0+(?:\.0+)?$/.test(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === "string" && value.length > 0;
}

function isMoneyResponse(value: unknown): value is string {
  return typeof value === "string" && MONEY_RESPONSE_PATTERN.test(value);
}

function isCreatedTime(value: unknown): value is string {
  return typeof value === "string" && Number.isFinite(Date.parse(value));
}
