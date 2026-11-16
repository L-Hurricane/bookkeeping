import { parseAccount, parseAccountList, type Account, type AccountKind } from "../account/account";
import {
  parseAccountGroup,
  parseAccountGroupList,
  type AccountGroup,
} from "../account/grouping";
import {
  parsePermanentDeletionPreview,
  type PermanentDeletionPreview,
} from "../account/permanent-deletion";
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

export interface CreateAccountInput {
  readonly name: string;
  readonly kind: AccountKind;
  readonly openingBalance: string;
}

export interface AccountApi {
  listAccounts(lifecycle?: "ACTIVE" | "ARCHIVED"): Promise<Account[]>;
  loadAccount(accountId: number): Promise<Account | null>;
  initializeAccount(openingBalance: string): Promise<Account>;
  createAccount(input: CreateAccountInput): Promise<Account>;
  renameAccount(accountId: number, name: string): Promise<Account>;
  archiveAccount(accountId: number): Promise<Account>;
  restoreAccount(accountId: number): Promise<Account>;
  previewPermanentDeletion(accountId: number): Promise<PermanentDeletionPreview>;
  executePermanentDeletion(accountId: number, previewToken: string): Promise<void>;
  listGroups(): Promise<AccountGroup[]>;
  createGroup(name: string): Promise<AccountGroup>;
  renameGroup(groupId: number, name: string): Promise<AccountGroup>;
  deleteGroup(groupId: number): Promise<void>;
  reorderGroups(ids: readonly number[]): Promise<AccountGroup[]>;
  placeAccount(accountId: number, groupId: number | null, orderedIds: readonly number[]): Promise<void>;
}

export function createAccountApi(http: HttpClient): AccountApi {
  const api: AccountApi = {
    async listAccounts(lifecycle) {
      const body = await http.request<unknown>({
        method: "GET",
        path: lifecycle === "ARCHIVED" ? "/api/v1/accounts?lifecycle=ARCHIVED" : "/api/v1/accounts",
        authenticated: true,
      });
      const accounts = parseAccountList(body);
      if (accounts === null) {
        throw new AccountResponseError("账户响应无法识别");
      }
      return accounts;
    },

    async loadAccount(accountId) {
      const active = await api.listAccounts();
      const visible = active.find((item) => item.id === accountId);
      if (visible !== undefined) {
        return visible;
      }
      const archived = await api.listAccounts("ARCHIVED");
      return archived.find((item) => item.id === accountId) ?? null;
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

    async createAccount(input) {
      const body = await http.request<unknown>({
        method: "POST",
        path: "/api/v1/accounts",
        body: {
          name: input.name,
          kind: input.kind,
          openingBalance: input.openingBalance,
        },
        authenticated: true,
      });
      const account = parseAccount(body);
      if (account === null) {
        throw new AccountResponseError("账户响应无法识别");
      }
      return account;
    },

    async renameAccount(accountId, name) {
      const body = await http.request<unknown>({
        method: "PUT",
        path: `/api/v1/accounts/${accountId}/name`,
        body: { name },
        authenticated: true,
      });
      const account = parseAccount(body);
      if (account === null || account.id !== accountId) {
        throw new AccountResponseError("账户响应无法识别");
      }
      return account;
    },

    async archiveAccount(accountId) {
      return changeLifecycle(http, accountId, "archive");
    },

    async restoreAccount(accountId) {
      return changeLifecycle(http, accountId, "restore");
    },

    async previewPermanentDeletion(accountId) {
      const body = await http.request<unknown>({
        method: "POST",
        path: `/api/v1/accounts/${accountId}/permanent-deletion/preview`,
        authenticated: true,
      });
      const preview = parsePermanentDeletionPreview(body);
      if (preview === null) {
        throw new AccountResponseError("永久删除预览响应无法识别");
      }
      return preview;
    },

    async executePermanentDeletion(accountId, previewToken) {
      await http.request<void>({
        method: "POST",
        path: `/api/v1/accounts/${accountId}/permanent-deletion`,
        body: { previewToken },
        authenticated: true,
      });
    },

    async listGroups() {
      const body = await http.request<unknown>({
        method: "GET",
        path: "/api/v1/account-groups",
        authenticated: true,
      });
      const groups = parseAccountGroupList(body);
      if (groups === null) {
        throw new AccountResponseError("分组响应无法识别");
      }
      return groups;
    },

    async createGroup(name) {
      const body = await http.request<unknown>({
        method: "POST",
        path: "/api/v1/account-groups",
        body: { name },
        authenticated: true,
      });
      const group = parseAccountGroup(body);
      if (group === null) {
        throw new AccountResponseError("分组响应无法识别");
      }
      return group;
    },

    async renameGroup(groupId, name) {
      const body = await http.request<unknown>({
        method: "PUT",
        path: `/api/v1/account-groups/${groupId}`,
        body: { name },
        authenticated: true,
      });
      const group = parseAccountGroup(body);
      if (group === null || group.id !== groupId) {
        throw new AccountResponseError("分组响应无法识别");
      }
      return group;
    },

    async deleteGroup(groupId) {
      await http.request<void>({
        method: "DELETE",
        path: `/api/v1/account-groups/${groupId}`,
        authenticated: true,
      });
    },

    async reorderGroups(ids) {
      const body = await http.request<unknown>({
        method: "PUT",
        path: "/api/v1/account-groups/order",
        body: { ids },
        authenticated: true,
      });
      const groups = parseAccountGroupList(body);
      if (groups === null) {
        throw new AccountResponseError("分组响应无法识别");
      }
      return groups;
    },

    async placeAccount(accountId, groupId, orderedIds) {
      await http.request<void>({
        method: "PUT",
        path: `/api/v1/accounts/${accountId}/placement`,
        body: { groupId, orderedIds },
        authenticated: true,
      });
    },
  };
  return api;
}

async function changeLifecycle(http: HttpClient, accountId: number, action: "archive" | "restore"): Promise<Account> {
  const body = await http.request<unknown>({
    method: "POST",
    path: `/api/v1/accounts/${accountId}/${action}`,
    authenticated: true,
  });
  const account = parseAccount(body);
  const expected = action === "archive" ? "ARCHIVED" : "ACTIVE";
  if (account === null || account.id !== accountId || account.lifecycle !== expected) {
    throw new AccountResponseError("账户响应无法识别");
  }
  return account;
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
