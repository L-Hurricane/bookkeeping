export interface Account {
  readonly id: number;
  readonly name: string;
  readonly currency: string;
  readonly openingBalance: string;
  readonly currentBalance: string;
  readonly createdTime: string;
}

export type AccountStartupRoute =
  | { readonly destination: "init" }
  | { readonly destination: "home"; readonly account: Account }
  | { readonly destination: "error"; readonly message: string };

export const ACCOUNT_CONTRACT_MESSAGE = "账户数据不符合约定";

const MONEY_RESPONSE_PATTERN = /^-?[0-9]+\.[0-9]{2}$/;

export function parseAccount(value: unknown): Account | null {
  if (!isRecord(value)) {
    return null;
  }
  const id = value.id;
  const name = value.name;
  const currency = value.currency;
  const openingBalance = value.openingBalance;
  const currentBalance = value.currentBalance;
  const createdTime = value.createdTime;
  if (typeof id !== "number" || !Number.isSafeInteger(id) || id <= 0) {
    return null;
  }
  if (!isNonEmptyString(name) || !isNonEmptyString(currency)) {
    return null;
  }
  if (!isMoneyResponse(openingBalance) || !isMoneyResponse(currentBalance) || !isCreatedTime(createdTime)) {
    return null;
  }
  return { id, name, currency, openingBalance, currentBalance, createdTime };
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
  if (items.length === 1) {
    const account = items[0];
    if (account === undefined) {
      return { destination: "error", message: ACCOUNT_CONTRACT_MESSAGE };
    }
    return { destination: "home", account };
  }
  return { destination: "error", message: ACCOUNT_CONTRACT_MESSAGE };
}

export function formatCurrentBalance(account: Account): string {
  if (account.currency === "CNY") {
    return `¥${account.currentBalance}`;
  }
  return `${account.currentBalance} ${account.currency}`;
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
