import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { createLoadGuard } from "../miniprogram/account/load-guard";
import { ApiError, createHttpClient, type HttpTransportRequest } from "../miniprogram/http/client";
import {
  canApplySearchResult,
  createSearchDebounce,
  isActiveSearchKeyword,
  parseTransactionSearch,
  searchLedgerRows,
  searchTransactionsPath,
  trimSearchKeyword,
} from "../miniprogram/transaction/transaction-search";
import { createTransactionApi, TransactionSearchResponseError } from "../miniprogram/services/transaction";

const created = "2020-01-01T00:00:00+08:00";

function searchItem(id: number, name: string, businessTime = "2026-09-12T20:00:00+08:00") {
  return {
    id,
    type: "EXPENSE",
    amount: "-12.00",
    displayName: name,
    category: { id: 3, name: "早餐" },
    tags: [{ id: 9, name: "外卖" }],
    businessTime,
  };
}

describe("transaction search", () => {
  it("trims keywords and builds the search path", () => {
    assert.equal(trimSearchKeyword("  牛奶  "), "牛奶");
    assert.equal(isActiveSearchKeyword(" "), false);
    assert.equal(searchTransactionsPath(42, "奶&鸡"), "/api/v1/accounts/42/transactions/search?name=%E5%A5%B6%26%E9%B8%A1");
  });

  it("rejects stale search results for account or keyword changes", () => {
    const guard = createLoadGuard();
    assert.equal(canApplySearchResult(guard, 1, 10, 10, "牛奶", "牛奶", 1), true);
    assert.equal(canApplySearchResult(guard, 1, 10, 11, "牛奶", "牛奶", 1), false);
    assert.equal(canApplySearchResult(guard, 1, 10, 10, "牛奶", "鸡蛋", 1), false);
    assert.equal(canApplySearchResult(guard, 1, 10, 10, "牛奶", "牛奶", 2), false);
    guard.pageAlive = false;
    assert.equal(canApplySearchResult(guard, 1, 10, 10, "牛奶", "牛奶", 1), false);
  });

  it("parses search responses and maps ledger rows without using Number for money", () => {
    const parsed = parseTransactionSearch({ items: [searchItem(7, "牛奶&鸡蛋&面包")] }, created);
    assert.notEqual(parsed, null);
    assert.equal(parsed?.items[0]?.amount, "-12.00");
    const rows = searchLedgerRows(parsed!.items);
    assert.equal(rows[0]?.title, "牛奶&鸡蛋&面包");
    assert.equal(rows[0]?.amountLabel, "¥-12.00");
  });

  it("debounces keyword changes before firing", () => {
    const timers: Array<{ callback: () => void; delayMs: number }> = [];
    const debounce = createSearchDebounce(
      300,
      (callback, delayMs) => {
        timers.push({ callback, delayMs });
        return timers.length;
      },
      () => {},
    );
    const seen: string[] = [];
    debounce.schedule("a", (keyword) => seen.push(keyword));
    debounce.schedule("ab", (keyword) => seen.push(keyword));
    assert.equal(timers.length, 2);
    assert.equal(seen.length, 0);
    timers[1]?.callback();
    assert.deepEqual(seen, ["ab"]);
  });

  it("loads search results through the transaction api", async () => {
    const requests: HttpTransportRequest[] = [];
    let calls = 0;
    const http = createHttpClient({
      baseUrl: "https://api.example",
      transport: async (request) => {
        requests.push(request);
        calls += 1;
        if (calls === 1) {
          return { status: 200, headers: {}, body: { items: [searchItem(2, "牛奶")] } };
        }
        return { status: 200, headers: {}, body: { items: [{ id: 2 }] } };
      },
      storage: { get: () => null, set: () => {}, remove: () => {} },
      now: () => new Date("2026-09-12T12:00:00+08:00"),
      onUnauthorized: () => {},
    });
    const api = createTransactionApi(http);
    const result = await api.searchTransactions(1001, "牛奶", created);
    assert.equal(result.items.length, 1);
    assert.equal(requests[0]?.path, "/api/v1/accounts/1001/transactions/search?name=%E7%89%9B%E5%A5%B6");
    await assert.rejects(() => api.searchTransactions(1001, "牛奶", created), TransactionSearchResponseError);
  });

  it("surfaces unauthorized search failures", async () => {
    const http = createHttpClient({
      baseUrl: "https://api.example",
      transport: async () => ({ status: 401, headers: {}, body: { error: { code: "UNAUTHORIZED" } } }),
      storage: { get: () => null, set: () => {}, remove: () => {} },
      now: () => new Date("2026-09-12T12:00:00+08:00"),
      onUnauthorized: () => {},
    });
    await assert.rejects(() => createTransactionApi(http).searchTransactions(1, "牛奶", created), (error: unknown) => {
      return error instanceof ApiError && error.status === 401;
    });
  });
});
