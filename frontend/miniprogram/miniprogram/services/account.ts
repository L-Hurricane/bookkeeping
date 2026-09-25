import { parseAccount, parseAccountList, type Account } from "../account/account";
import { ApiError, type HttpClient } from "../http/client";

export class AccountResponseError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "AccountResponseError";
  }
}

export class AccountReconciliationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "AccountReconciliationError";
  }
}

export interface AccountApi {
  listAccounts(): Promise<Account[]>;
  initializeAccount(openingBalance: string): Promise<Account>;
}

export function createAccountApi(http: HttpClient): AccountApi {
  return {
    async listAccounts() {
      const body = await http.request<unknown>({
        method: "GET",
        path: "/api/v1/accounts",
        authenticated: true,
      });
      const accounts = parseAccountList(body);
      if (accounts === null) {
        throw new AccountResponseError("账户响应无法识别");
      }
      return accounts;
    },

    async initializeAccount(openingBalance) {
      try {
        const body = await http.request<unknown>({
          method: "POST",
          path: "/api/v1/accounts/initialize",
          body: { openingBalance },
          authenticated: true,
        });
        const account = parseAccount(body);
        if (account === null) {
          throw new AccountResponseError("账户响应无法识别");
        }
        return account;
      } catch (error) {
        if (error instanceof ApiError && error.status === 409 && error.code === "ACCOUNT_ALREADY_INITIALIZED") {
          return reconcileInitializedAccount(http);
        }
        throw error;
      }
    },
  };
}

async function reconcileInitializedAccount(http: HttpClient): Promise<Account> {
  let accounts: Account[];
  try {
    accounts = await createAccountApi(http).listAccounts();
  } catch {
    throw new AccountReconciliationError("资金账户已经初始化，但无法读取账户");
  }
  if (accounts.length !== 1) {
    throw new AccountReconciliationError(
      accounts.length === 0 ? "资金账户已经初始化，但没有读到账户" : "账户数据不符合约定",
    );
  }
  const account = accounts[0];
  if (account === undefined) {
    throw new AccountReconciliationError("资金账户已经初始化，但没有读到账户");
  }
  return account;
}
