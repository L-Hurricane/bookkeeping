import { moneyToneClass } from "./money-tone";

export type AccountKind = "NORMAL" | "CREDIT";
export type AccountLifecycle = "ACTIVE" | "ARCHIVED";

export interface Account {
  readonly id: number;
  readonly name: string;
  readonly kind: AccountKind;
  readonly currency: string;
  readonly openingBalance: string;
  readonly currentBalance: string;
  readonly createdTime: string;
  readonly lifecycle: AccountLifecycle;
}

export type AccountStartupRoute =
  | { readonly destination: "init" }
  | { readonly destination: "list"; readonly accounts: readonly Account[] }
  | { readonly destination: "error"; readonly message: string };

export const ACCOUNT_CONTRACT_MESSAGE = "账户数据不符合约定";

export const MONEY_RESPONSE_PATTERN = /^-?[0-9]+\.[0-9]{2}$/;

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
  const lifecycle = value.lifecycle;
  if (typeof id !== "number" || !Number.isSafeInteger(id) || id <= 0) {
    return null;
  }
  if (!isNonEmptyString(name) || !isNonEmptyString(currency) || !isAccountKind(kind) || !isAccountLifecycle(lifecycle)) {
    return null;
  }
  if (!isMoneyResponse(openingBalance) || !isMoneyResponse(currentBalance) || !isCreatedTime(createdTime)) {
    return null;
  }
  return { id, name, kind, currency, openingBalance, currentBalance, createdTime, lifecycle };
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

/** An empty active list is initialization only when the user also has no archived account. */
export function routeActiveAccounts(active: readonly Account[], archived: readonly Account[]): AccountStartupRoute {
  if (active.length === 0 && archived.length === 0) {
    return { destination: "init" };
  }
  return { destination: "list", accounts: active };
}

export interface AccountPickerCard {
  readonly id: number;
  readonly name: string;
  readonly kindLabel: string;
  readonly balanceLabel: string;
  readonly balanceTone: string;
  readonly selected: boolean;
}

export function accountPickerCards(accounts: readonly Account[], selectedId: number, query: string): {
  readonly accountRows: AccountPickerCard[];
  readonly accountSheetHeight: number;
} {
  const needle = query.trim();
  const accountRows = accounts.filter((account) => needle.length === 0 || account.name.includes(needle)).map((account) => ({
    id: account.id,
    name: account.name,
    kindLabel: accountKindLabel(account.kind),
    balanceLabel: formatCurrentBalance(account),
    balanceTone: moneyToneClass(account.currentBalance),
    selected: account.id === selectedId,
  }));
  return { accountRows, accountSheetHeight: Math.min(Math.max(accountRows.length, 1) * 128, 640) };
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

function isAccountLifecycle(value: unknown): value is AccountLifecycle {
  return value === "ACTIVE" || value === "ARCHIVED";
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
