import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { createLoadGuard } from "../miniprogram/account/load-guard";
import { ApiError, createHttpClient, type HttpTransportRequest } from "../miniprogram/http/client";
import { saveSession } from "../miniprogram/session/session";
import type { KeyValueStorage } from "../miniprogram/storage/key-value";
import {
  buildLedgerLoadSetDataPatch,
  canApplySearchResult,
  createSearchDebounce,
  invalidateSearchOnInputChange,
  isActiveSearchKeyword,
  parseTransactionSearch,
  searchDraftDiffersFromCommitted,
  searchLedgerRows,
  searchTransactionsPath,
  shouldPreserveSearchRowsOnLedgerLoad,
  shouldRefreshSearchAfterDetailReturn,
  shouldRefreshSearchOnListShow,
  shouldRunDebouncedSearch,
  shouldRunSearchOnMonthChange,
  trimSearchKeyword,
} from "../miniprogram/transaction/transaction-search";
import {
  noteListStale,
  noteSavedTransaction,
  openTransactionDetail,
  showOpenList,
  type OpenList,
} from "../miniprogram/navigation/scroll-restore";
import { createTransactionApi, TransactionSearchResponseError } from "../miniprogram/services/transaction";

const created = "2020-01-01T00:00:00+08:00";
const activeSession = { token: "opaque-token", expiresAt: "2099-01-01T00:00:00Z" };

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
    assert.equal(canApplySearchResult(guard, 1, 10, 10, "牛奶", "牛奶", 1, "牛奶"), true);
    assert.equal(canApplySearchResult(guard, 1, 10, 11, "牛奶", "牛奶", 1, "牛奶"), false);
    assert.equal(canApplySearchResult(guard, 1, 10, 10, "牛奶", "鸡蛋", 1, "牛奶"), false);
    assert.equal(canApplySearchResult(guard, 1, 10, 10, "牛奶", "牛奶", 2, "牛奶"), false);
    assert.equal(canApplySearchResult(guard, 1, 10, 10, "牛奶", "牛奶", 1, "牛奶"), true);
    assert.equal(canApplySearchResult(guard, 1, 10, 10, "牛奶", "牛奶x", 1, "牛奶"), false);
    guard.pageAlive = false;
    assert.equal(canApplySearchResult(guard, 1, 10, 10, "牛奶", "牛奶", 1, "牛奶"), false);
  });

  it("invalidates in-flight search when draft diverges or input changes during loading", () => {
    assert.equal(searchDraftDiffersFromCommitted("牛奶", "牛奶"), false);
    assert.equal(searchDraftDiffersFromCommitted("牛奶", "牛"), true);
    const idle = invalidateSearchOnInputChange("牛奶", "牛", false, 3);
    assert.equal(idle.nextGeneration, 4);
    assert.equal(idle.clearSearchLoading, false);
    const loading = invalidateSearchOnInputChange("牛奶", "牛奶", true, 4);
    assert.equal(loading.nextGeneration, 5);
    assert.equal(loading.clearSearchLoading, true);
    const firstDraft = invalidateSearchOnInputChange("", "牛", false, 0);
    assert.equal(firstDraft.nextGeneration, 0);
  });

  it("reruns debounced search after invalidation even when keyword matches committed", () => {
    assert.equal(shouldRunDebouncedSearch("牛奶", "牛奶", false, 2, 2), false);
    assert.equal(shouldRunDebouncedSearch("牛奶", "牛奶", true, 2, 2), true);
    assert.equal(shouldRunDebouncedSearch("牛奶", "牛奶", false, 3, 2), true);
    assert.equal(shouldRunDebouncedSearch("牛奶", "牛", false, 2, 2), true);
    assert.equal(shouldRunDebouncedSearch("", " ", false, 0, 0), true);
  });

  it("refreshes search after dirty detail return but not on month change", () => {
    assert.equal(shouldRefreshSearchAfterDetailReturn("牛奶"), true);
    assert.equal(shouldRefreshSearchAfterDetailReturn(" "), false);
    assert.equal(shouldPreserveSearchRowsOnLedgerLoad("牛奶"), true);
    assert.equal(shouldRunSearchOnMonthChange("牛奶"), false);
    assert.equal(shouldRunSearchOnMonthChange(""), true);
  });

  it("keeps search rows when a late ledger patch applies during active search", () => {
    const searchRows = searchLedgerRows([searchItem(9, "牛奶")]);
    const page = {
      rows: searchRows,
      empty: false,
      searchEmpty: false,
      balanceLabel: "¥1.00",
    };
    const latePatch = buildLedgerLoadSetDataPatch("牛奶", [], {
      phase: "ready",
      balanceLabel: "¥9.00",
      monthLabel: "2026年9月",
      incomeLabel: "¥0.00",
      expenseLabel: "¥0.00",
      surplusLabel: "¥0.00",
      countLabel: "0 笔收支",
      errorMessage: "",
      locateMiss: "",
      highlightKey: "",
    });
    assert.equal("rows" in latePatch, false);
    assert.equal("empty" in latePatch, false);
    assert.equal("searchEmpty" in latePatch, false);
    const merged = { ...page, ...latePatch };
    assert.deepEqual(merged.rows, searchRows);
    assert.equal(merged.balanceLabel, "¥9.00");
    assert.equal(merged.searchEmpty, false);
  });

  it("writes month ledger rows when search is inactive at ledger apply time", () => {
    const searchRows = searchLedgerRows([searchItem(9, "牛奶")]);
    const page = { rows: searchRows, empty: false, searchEmpty: false };
    const monthPatch = buildLedgerLoadSetDataPatch("", [], {
      phase: "ready",
      balanceLabel: "¥0.00",
      monthLabel: "2026年9月",
      incomeLabel: "¥0.00",
      expenseLabel: "¥0.00",
      surplusLabel: "¥0.00",
      countLabel: "0 笔收支",
      errorMessage: "",
      locateMiss: "",
      highlightKey: "",
    });
    assert.deepEqual(monthPatch.rows, []);
    assert.equal(monthPatch.empty, true);
    const merged = { ...page, ...monthPatch };
    assert.deepEqual(merged.rows, []);
    assert.equal(merged.empty, true);
  });

  it("refreshes search on dirty return transitions but not on keep or user month change", () => {
    const home = (month: string): OpenList => ({ month, locateMonth: null, visit: null });
    const keyword = "牛奶";

    const deleteReturn = showOpenList(noteListStale(openTransactionDetail(home("2026-09"), 480)));
    assert.equal(shouldRefreshSearchOnListShow(deleteReturn.show, keyword), true);

    const monthMove = showOpenList(noteSavedTransaction(openTransactionDetail(home("2026-09"), 480), "2026-10"));
    assert.equal(shouldRefreshSearchOnListShow(monthMove.show, keyword), true);

    const readOnly = showOpenList(openTransactionDetail(home("2026-09"), 480));
    assert.equal(shouldRefreshSearchOnListShow(readOnly.show, keyword), false);

    assert.equal(shouldRunSearchOnMonthChange(keyword), false);
    assert.equal(shouldRefreshSearchOnListShow({ refresh: "reload", scope: "account", locate: true }, keyword), false);
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
    const storage = memoryStorage();
    saveSession(storage, activeSession);
    const http = createHttpClient({
      baseUrl: "https://api.example",
      transport: async (request) => {
        requests.push(request);
        calls += 1;
        if (calls === 1) {
          return { statusCode: 200, data: { items: [searchItem(2, "牛奶")] } };
        }
        return { statusCode: 200, data: { items: [{ id: 2 }] } };
      },
      storage,
      now: () => new Date("2026-09-12T12:00:00+08:00"),
      onUnauthorized: () => {},
    });
    const api = createTransactionApi(http);
    const result = await api.searchTransactions(1001, "牛奶", created);
    assert.equal(result.items.length, 1);
    assert.equal(requests[0]?.url, "https://api.example/api/v1/accounts/1001/transactions/search?name=%E7%89%9B%E5%A5%B6");
    await assert.rejects(() => api.searchTransactions(1001, "牛奶", created), TransactionSearchResponseError);
  });

  it("surfaces unauthorized search failures", async () => {
    const storage = memoryStorage();
    saveSession(storage, activeSession);
    const http = createHttpClient({
      baseUrl: "https://api.example",
      transport: async () => ({ statusCode: 401, data: { error: { code: "UNAUTHORIZED", message: "未授权" } } }),
      storage,
      now: () => new Date("2026-09-12T12:00:00+08:00"),
      onUnauthorized: () => {},
    });
    await assert.rejects(() => createTransactionApi(http).searchTransactions(1, "牛奶", created), (error: unknown) => {
      return error instanceof ApiError && error.status === 401;
    });
  });
});
