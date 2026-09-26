import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { parseAccount, routeAfterAccountQuery, type Account } from "../miniprogram/account/account";
import { beginLoad, beginLogout, canApplyLoadResult, createLoadGuard, endLogout } from "../miniprogram/account/load-guard";
import { logoutFailureView, resumeAfterLogoutFailure, selectedMonthDuringLogout } from "../miniprogram/account/logout-view";
import { normalizeOpeningBalance } from "../miniprogram/account/opening-balance";
import { createHttpClient, type HttpTransportRequest, type HttpTransportResponse } from "../miniprogram/http/client";
import { saveSession } from "../miniprogram/session/session";
import {
  AccountReconciliationError,
  AccountResponseError,
  createAccountApi,
} from "../miniprogram/services/account";
import type { KeyValueStorage } from "../miniprogram/storage/key-value";

const account: Account = {
  id: 1001,
  name: "微信",
  currency: "CNY",
  openingBalance: "100.00",
  currentBalance: "60.50",
  createdTime: "2026-09-01T10:00:00+08:00",
};

function memoryStorage(): KeyValueStorage {
  let value: string | undefined;
  return {
    getItem() {
      return value;
    },
    setItem(_key, next) {
      value = next;
    },
    removeItem() {
      value = undefined;
    },
  };
}

function apiFor(responder: (request: HttpTransportRequest) => HttpTransportResponse) {
  const storage = memoryStorage();
  saveSession(storage, { token: "opaque-token", expiresAt: "2099-01-01T00:00:00Z" });
  const http = createHttpClient({
    baseUrl: "http://127.0.0.1:8080",
    storage,
    now: () => new Date("2026-09-25T08:00:00Z"),
    onUnauthorized: () => undefined,
    transport: async (request) => responder(request),
  });
  return createAccountApi(http);
}

describe("account load guard", () => {
  it("ignores a result after logout, unload, or a newer load", () => {
    const guard = createLoadGuard();
    const first = beginLoad(guard);
    const second = beginLoad(guard);
    assert.equal(first === null || second === null, false);
    assert.equal(canApplyLoadResult(guard, first ?? 0), false);
    assert.equal(canApplyLoadResult(guard, second ?? 0), true);

    guard.loggingOut = true;
    assert.equal(canApplyLoadResult(guard, second ?? 0), false);
    assert.equal(beginLoad(guard), null);

    guard.loggingOut = false;
    guard.pageAlive = false;
    assert.equal(canApplyLoadResult(guard, second ?? 0), false);
    assert.equal(beginLoad(guard), null);
  });

  it("keeps an in-flight load stale after logout fails", () => {
    const guard = createLoadGuard();
    const oldGeneration = beginLoad(guard);
    assert.equal(beginLogout(guard), true);
    endLogout(guard);
    assert.equal(canApplyLoadResult(guard, oldGeneration ?? 0), false);

    const next = beginLoad(guard);
    assert.notEqual(next, null);
    assert.notEqual(next, oldGeneration);
    assert.equal(canApplyLoadResult(guard, next ?? 0), true);
  });
});

describe("logout failure", () => {
  it("keeps the current account or ledger read state", () => {
    const loading = logoutFailureView({ phase: "account-loading", accountId: 0, rows: [] });
    assert.equal(loading.phase, "account-loading");
    assert.equal(loading.accountId, 0);
    assert.equal(loading.loggingOut, false);
    assert.equal(loading.logoutErrorMessage, "退出失败，请稍后重试");

    const ready = logoutFailureView({
      phase: "ready",
      rows: [{ title: "九月支出" }],
      balanceLabel: "¥1300.00",
    });
    assert.equal(ready.phase, "ready");
    assert.deepEqual(ready.rows, [{ title: "九月支出" }]);
    assert.equal(ready.balanceLabel, "¥1300.00");
    assert.notEqual(ready.phase, "ledger-error");
  });

  it("resumes a new load after logout failure and leaves finished states alone", () => {
    const guard = createLoadGuard();
    const oldGeneration = beginLoad(guard);
    assert.equal(beginLogout(guard), true);
    endLogout(guard);
    assert.equal(canApplyLoadResult(guard, oldGeneration ?? 0), false);
    const next = beginLoad(guard);
    assert.equal(canApplyLoadResult(guard, next ?? 0), true);

    assert.equal(resumeAfterLogoutFailure("account-loading"), "account");
    assert.equal(resumeAfterLogoutFailure("ledger-loading"), "ledger");
    assert.equal(resumeAfterLogoutFailure("ready"), null);
    assert.equal(resumeAfterLogoutFailure("account-error"), null);
    assert.equal(resumeAfterLogoutFailure("ledger-error"), null);
  });

  it("keeps the selected month unchanged while logout is in progress", () => {
    assert.equal(selectedMonthDuringLogout(true, "2026-09", "2026-08"), "2026-09");
    assert.equal(selectedMonthDuringLogout(true, "2026-09", "2026-10"), "2026-09");
    assert.equal(selectedMonthDuringLogout(false, "2026-09", "2026-08"), "2026-08");
  });
});

describe("opening balance", () => {
  it("normalizes a blank opening balance to zero and keeps valid decimal strings", () => {
    assert.equal(normalizeOpeningBalance(""), "0");
    assert.equal(normalizeOpeningBalance("   "), "0");
    assert.equal(normalizeOpeningBalance("0"), "0");
    assert.equal(normalizeOpeningBalance("0.00"), "0.00");
    assert.equal(normalizeOpeningBalance("100"), "100");
    assert.equal(normalizeOpeningBalance("100.00"), "100.00");
    assert.equal(normalizeOpeningBalance("-100"), "-100");
    assert.equal(normalizeOpeningBalance("-100.50"), "-100.50");
  });

  it("rejects non-numeric values and more than two decimal places", () => {
    assert.equal(normalizeOpeningBalance("1.234"), null);
    assert.equal(normalizeOpeningBalance("abc"), null);
  });
});

describe("account query routing", () => {
  it("routes an empty list to init and one account to home", async () => {
    const empty = apiFor(() => ({ statusCode: 200, data: { items: [] } }));
    assert.deepEqual(routeAfterAccountQuery(await empty.listAccounts()), { destination: "init" });

    const one = apiFor(() => ({ statusCode: 200, data: { items: [account] } }));
    assert.deepEqual(routeAfterAccountQuery(await one.listAccounts()), { destination: "home", account });
  });

  it("rejects malformed money and createdTime", () => {
    assert.equal(parseAccount({ ...account, openingBalance: "100" }), null);
    assert.equal(parseAccount({ ...account, currentBalance: "60.5" }), null);
    assert.equal(parseAccount({ ...account, createdTime: "tomorrow" }), null);
    assert.equal(parseAccount(account)?.currentBalance, "60.50");
  });

  it("rejects a malformed account response", async () => {
    const accounts = apiFor(() => ({ statusCode: 200, data: { items: [{ id: "1001" }] } }));
    await assert.rejects(() => accounts.listAccounts(), (error: unknown) => error instanceof AccountResponseError);
  });

  it("treats more than one account as a contract violation", () => {
    const route = routeAfterAccountQuery([account, { ...account, id: 1002 }]);
    assert.equal(route.destination, "error");
  });

  it("does not turn a failed query into an empty account list", async () => {
    const accounts = apiFor(() => ({
      statusCode: 500,
      data: { error: { code: "INTERNAL_ERROR", message: "服务暂时不可用", requestId: "req-3" } },
    }));
    await assert.rejects(() => accounts.listAccounts());
  });
});

describe("initialize account", () => {
  it("returns the created account", async () => {
    const accounts = apiFor((request) => {
      assert.equal(request.url, "http://127.0.0.1:8080/api/v1/accounts/initialize");
      assert.equal(request.data, JSON.stringify({ openingBalance: "100.00" }));
      return { statusCode: 201, data: { ...account, currentBalance: "100.00" } };
    });
    const created = await accounts.initializeAccount("100.00");
    assert.equal(created.currentBalance, "100.00");
    assert.equal(created.name, "微信");
  });

  it("re-queries after ACCOUNT_ALREADY_INITIALIZED and returns the existing account", async () => {
    const accounts = apiFor((request) => {
      if (request.url.endsWith("/initialize")) {
        return {
          statusCode: 409,
          data: { error: { code: "ACCOUNT_ALREADY_INITIALIZED", message: "资金账户已经初始化", requestId: "req-1" } },
        };
      }
      return { statusCode: 200, data: { items: [account] } };
    });
    assert.deepEqual(await accounts.initializeAccount("0"), account);
  });

  it("does not invent an account when re-query is still empty", async () => {
    const accounts = apiFor((request) => {
      if (request.url.endsWith("/initialize")) {
        return {
          statusCode: 409,
          data: { error: { code: "ACCOUNT_ALREADY_INITIALIZED", message: "资金账户已经初始化", requestId: "req-2" } },
        };
      }
      return { statusCode: 200, data: { items: [] } };
    });
    await assert.rejects(
      () => accounts.initializeAccount("0"),
      (error: unknown) => error instanceof AccountReconciliationError,
    );
  });
});
