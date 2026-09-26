import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { createHttpClient, type HttpTransportRequest } from "../miniprogram/http/client";
import { saveSession } from "../miniprogram/session/session";
import {
  categoryPath,
  createStatisticsApi,
  monthlyPath,
  StatisticsResponseError,
  tagPath,
} from "../miniprogram/services/statistics";
import { parseTransactionList, type CategoryStatisticsItem, type StatisticsBundle, type StatisticsType, type TransactionListItem } from "../miniprogram/statistics/contract";
import { statisticsDetailPath, statisticsPagePath, transactionListPath } from "../miniprogram/statistics/drilldown";
import { canOpenStatistics, parseStatisticsDetailContext } from "../miniprogram/statistics/navigation";
import {
  categoryRows,
  failStatistics,
  failTransactionDetail,
  openStatistics,
  openTransactionDetail,
  requestStatistics,
  requestTransactionDetail,
  settleStatistics,
  settleTransactionDetail,
  statisticsIsEmpty,
  tagRows,
  transactionRows,
} from "../miniprogram/statistics/screen";
import type { KeyValueStorage } from "../miniprogram/storage/key-value";

const createdTime = "2026-08-01T00:00:00+08:00";

describe("statistics loading", () => {
  it("loads one month and keeps income distinct from expense", async () => {
    const requests: string[] = [];
    const api = apiFor((request) => {
      requests.push(request.url);
      if (request.url.includes("/statistics/monthly")) return { statusCode: 200, data: monthly("2026-09", 2, "100.50", "35.00", "65.50") };
      if (request.url.includes("/statistics/categories")) return { statusCode: 200, data: categories("2026-09", "EXPENSE") };
      return { statusCode: 200, data: tags("2026-09", "EXPENSE") };
    });
    const bundle = await api.load(1001, "2026-09", "EXPENSE");
    assert.equal(monthlyPath(1001, "2026-09"), "/api/v1/accounts/1001/statistics/monthly?month=2026-09");
    assert.equal(categoryPath(1001, "2026-09", "EXPENSE"), "/api/v1/accounts/1001/statistics/categories?month=2026-09&type=EXPENSE");
    assert.equal(tagPath(1001, "2026-09", "EXPENSE"), "/api/v1/accounts/1001/statistics/tags?month=2026-09&type=EXPENSE");
    assert.equal(requests.length, 3);
    assert.equal(bundle.monthly.netIncome, "100.50");
    assert.equal(bundle.monthly.netExpense, "35.00");
    assert.equal(bundle.categories.type, "EXPENSE");
    assert.equal(bundle.tags.type, "EXPENSE");
    assert.equal(bundle.categories.items[0]?.categoryName, "餐饮");
    assert.equal(bundle.categories.items[0]?.children[0]?.categoryName, "午餐");
    assert.equal(bundle.categories.items[1]?.kind, "UNCATEGORIZED");
    assert.equal(bundle.tags.items[0]?.kind, "TAG");
    assert.equal(bundle.tags.items[1]?.kind, "UNTAGGED");
  });

  it("clears the previous month and ignores a late response", () => {
    const first = requestStatistics(openStatistics("2026-09", "EXPENSE"), "2026-09", "EXPENSE");
    const second = requestStatistics(first.screen, "2026-08", "EXPENSE");
    assert.equal(second.screen.phase, "loading");
    assert.equal(second.screen.bundle, null);
    assert.equal(second.screen.month, "2026-08");
    const stale = settleStatistics(second.screen, first.generation, bundle("2026-09", "EXPENSE"), true);
    assert.equal(stale.phase, "loading");
    assert.equal(stale.month, "2026-08");
    assert.equal(stale.bundle, null);
    const current = settleStatistics(stale, second.generation, bundle("2026-08", "EXPENSE"), true);
    assert.equal(current.phase, "ready");
    assert.equal(current.bundle?.monthly.month, "2026-08");
    assert.equal(settleStatistics(current, second.generation, bundle("2026-08", "EXPENSE"), false), current);
  });

  it("does not apply an expense result after the type changes to income", () => {
    const expense = requestStatistics(openStatistics("2026-09", "EXPENSE"), "2026-09", "EXPENSE");
    const income = requestStatistics(expense.screen, "2026-09", "INCOME");
    const stale = settleStatistics(income.screen, expense.generation, bundle("2026-09", "EXPENSE"), true);
    assert.equal(stale.type, "INCOME");
    assert.equal(stale.bundle, null);
    const ready = settleStatistics(stale, income.generation, bundle("2026-09", "INCOME"), true);
    assert.equal(ready.bundle?.categories.type, "INCOME");
    assert.equal(ready.bundle?.tags.type, "INCOME");
  });

  it("keeps an empty month ready and a failed read as an error", () => {
    const requested = requestStatistics(openStatistics("2026-09", "EXPENSE"), "2026-09", "EXPENSE");
    const empty = settleStatistics(requested.screen, requested.generation, bundle("2026-09", "EXPENSE", 0), true);
    assert.equal(empty.phase, "ready");
    assert.equal(statisticsIsEmpty(empty.bundle ?? bundle("2026-09", "EXPENSE")), true);
    const failed = failStatistics(requested.screen, requested.generation, "统计读取失败，请重新加载", true);
    assert.equal(failed.phase, "error");
    assert.equal(failed.bundle, null);
  });
});

describe("statistics drill-down", () => {
  it("builds category, uncategorized, tag, and untagged queries with the selected type", () => {
    assert.equal(
      transactionListPath(1001, "2026-09", "EXPENSE", { kind: "category", categoryId: 203, title: "午餐" }),
      "/api/v1/accounts/1001/transactions?month=2026-09&type=EXPENSE&categoryId=203",
    );
    assert.equal(
      transactionListPath(1001, "2026-09", "INCOME", { kind: "uncategorized", title: "未分类" }),
      "/api/v1/accounts/1001/transactions?month=2026-09&type=INCOME&uncategorizedOnly=true",
    );
    assert.equal(
      transactionListPath(1001, "2026-09", "EXPENSE", { kind: "tag", tagId: 301, title: "外卖" }),
      "/api/v1/accounts/1001/transactions?month=2026-09&type=EXPENSE&tagId=301",
    );
    const untagged = transactionListPath(1001, "2026-09", "EXPENSE", { kind: "untagged", title: "无标签" });
    assert.equal(untagged, "/api/v1/accounts/1001/transactions?month=2026-09&type=EXPENSE&untaggedOnly=true");
    assert.equal(untagged.includes("tagId"), false);
    assert.equal(untagged.includes("uncategorizedOnly"), false);
    const rows = categoryRows(bundle("2026-09", "EXPENSE").categories.items);
    assert.equal(rows[0]?.drill, null);
    assert.equal(rows[1]?.drill?.kind, "category");
    assert.equal(rows[1]?.drill?.kind === "category" ? rows[1].drill.categoryId : 0, 203);
    assert.equal(rows[1]?.depth, 1);
    assert.equal(rows[2]?.drill?.kind, "uncategorized");
    const tags = tagRows(bundle("2026-09", "EXPENSE").tags.items);
    assert.deepEqual(tags.map((item) => item.drill?.kind), ["tag", "untagged"]);
  });

  it("does not drill an aggregate parent and still drills a leaf, a child, and uncategorized", () => {
    const rows = categoryRows([
      category(201, "餐饮", [{ categoryId: 203, categoryName: "午餐", transactionCount: 1, amount: "30.00" }]),
      category(204, "交通", []),
      { kind: "UNCATEGORIZED", categoryId: null, categoryName: "未分类", transactionCount: 1, amount: "5.00", children: [] },
    ]);
    assert.equal(rows[0]?.drill, null);
    assert.equal(transactionListPath(1001, "2026-09", "EXPENSE", rows[1]?.drill ?? { kind: "uncategorized", title: "" }),
      "/api/v1/accounts/1001/transactions?month=2026-09&type=EXPENSE&categoryId=203");
    assert.equal(rows[2]?.drill?.kind === "category" ? rows[2].drill.categoryId : 0, 204);
    assert.equal(transactionListPath(1001, "2026-09", "INCOME", rows[3]?.drill ?? { kind: "category", categoryId: 1, title: "" }),
      "/api/v1/accounts/1001/transactions?month=2026-09&type=INCOME&uncategorizedOnly=true");
  });

  it("rejects a detail query that combines tag id with untagged", () => {
    const query = {
      accountId: "1001",
      month: "2026-09",
      type: "EXPENSE",
      accountCreatedTime: createdTime,
      title: "无标签",
      drill: "untagged",
      tagId: "301",
    };
    assert.equal(parseStatisticsDetailContext(query), null);
    assert.equal(parseStatisticsDetailContext({ ...query, tagId: undefined })?.drill.kind, "untagged");
  });

  it("restores an encoded detail title and rejects a broken escape", () => {
    const tag = statisticsDetailPath(1001, "2026-09", "EXPENSE", createdTime, { kind: "tag", tagId: 301, title: "HG外卖" });
    assert.equal(parseStatisticsDetailContext(rawQuery(tag))?.drill.title, "HG外卖");
    const category = statisticsDetailPath(1001, "2026-09", "EXPENSE", createdTime, { kind: "category", categoryId: 202, title: "早餐" });
    const categoryContext = parseStatisticsDetailContext(rawQuery(category));
    assert.equal(categoryContext?.drill.kind, "category");
    assert.equal(categoryContext?.drill.title, "早餐");
    const special = statisticsDetailPath(1001, "2026-09", "INCOME", createdTime, { kind: "uncategorized", title: "A & 100% + 空格" });
    assert.equal(parseStatisticsDetailContext(rawQuery(special))?.drill.title, "A & 100% + 空格");
    const broken = rawQuery(tag);
    broken.title = "%E5%A4";
    assert.doesNotThrow(() => parseStatisticsDetailContext(broken));
    assert.equal(parseStatisticsDetailContext(broken), null);
    broken.title = "%";
    assert.equal(parseStatisticsDetailContext(broken), null);
  });

  it("keeps backend transaction order and ignores a late detail response", async () => {
    const api = apiFor(() => ({
      statusCode: 200,
      data: {
        month: "2026-09",
        items: [transaction(7, "2026-09-02T08:00:00+08:00"), transaction(3, "2026-09-20T08:00:00+08:00")],
      },
    }));
    const list = await api.listTransactions(1001, "2026-09", "EXPENSE", { kind: "untagged", title: "无标签" }, createdTime);
    assert.deepEqual(transactionRows(list.items).map((item) => item.id), [7, 3]);
    assert.equal(transactionRows([unnamed()]).map((item) => item.title)[0], "未命名交易");
    const first = requestTransactionDetail(openTransactionDetail());
    const second = requestTransactionDetail(first.screen);
    const stale = settleTransactionDetail(second.screen, first.generation, [transaction(7, "2026-09-02T08:00:00+08:00")], true);
    assert.equal(stale.phase, "loading");
    assert.equal(stale.items.length, 0);
    const current = settleTransactionDetail(second.screen, second.generation, list.items, true);
    assert.deepEqual(current.items.map((item) => item.id), [7, 3]);
    assert.equal(failTransactionDetail(second.screen, second.generation, "明细读取失败，请重新加载", true).phase, "error");
  });
});

describe("transaction list business time", () => {
  it("rejects a business time that is not a real second in the requested month after account creation", async () => {
    const drill = { kind: "uncategorized" as const, title: "未分类" };
    const cases = [
      "2026-02-31T12:00:00+08:00",
      "2026-09-02T08:00:00Z",
      "2026-09-02T08:00+08:00",
      "2026-08-31T23:59:59+08:00",
      "2026-09-01T00:00:00+08:00",
    ];
    for (const businessTime of cases) {
      const created = businessTime === "2026-09-01T00:00:00+08:00" ? "2026-09-15T00:00:00+08:00" : createdTime;
      assert.equal(parseTransactionList(listBody(businessTime), "2026-09", "EXPENSE", created), null);
      const api = apiFor(() => ({ statusCode: 200, data: listBody(businessTime) }));
      await assert.rejects(() => api.listTransactions(1001, "2026-09", "EXPENSE", drill, created), StatisticsResponseError);
    }
    const accepted = parseTransactionList(listBody("2026-09-15T00:00:00+08:00"), "2026-09", "EXPENSE", "2026-09-15T00:00:00+08:00");
    assert.equal(accepted?.items[0]?.businessTime, "2026-09-15T00:00:00+08:00");
  });
});

describe("statistics contract", () => {
  it("rejects a malformed statistics response", async () => {
    const broken = apiFor((request) => {
      if (request.url.includes("/statistics/monthly")) return { statusCode: 200, data: monthly("2026-09", 1, "10.00", "3.00", "9.00") };
      if (request.url.includes("/statistics/categories")) return { statusCode: 200, data: categories("2026-09", "EXPENSE") };
      return { statusCode: 200, data: { ...tags("2026-09", "EXPENSE"), overlap: false } };
    });
    await assert.rejects(() => broken.load(1001, "2026-09", "EXPENSE"), StatisticsResponseError);
    const mismatched = apiFor(() => ({
      statusCode: 200,
      data: { month: "2026-09", items: [{ id: 1, type: "INCOME", amount: "5.00", tags: [], businessTime: "2026-09-02T08:00:00+08:00" }] },
    }));
    await assert.rejects(
      () => mismatched.listTransactions(1001, "2026-09", "EXPENSE", { kind: "uncategorized", title: "未分类" }, createdTime),
      (error: unknown) => error instanceof StatisticsResponseError,
    );
  });

  it("opens statistics from a known account and preserves the selected month", () => {
    assert.equal(canOpenStatistics("ready", false), true);
    assert.equal(canOpenStatistics("account-loading", false), false);
    assert.equal(canOpenStatistics("ready", true), false);
    assert.equal(
      statisticsPagePath(1001, "2026-09", createdTime),
      `/pages/statistics/index?accountId=1001&month=2026-09&accountCreatedTime=${encodeURIComponent(createdTime)}`,
    );
    assert.equal(
      statisticsDetailPath(1001, "2026-09", "EXPENSE", createdTime, { kind: "category", categoryId: 203, title: "午餐" }).includes("type=EXPENSE"),
      true,
    );
  });
});

function bundle(month: string, type: StatisticsType, count = 2): StatisticsBundle {
  const income = type === "INCOME" ? "100.50" : "0.00";
  const expense = type === "EXPENSE" ? "35.00" : "0.00";
  const surplus = type === "INCOME" ? "100.50" : type === "EXPENSE" && count === 0 ? "0.00" : "-35.00";
  return {
    monthly: {
      month,
      transactionCount: count,
      netIncome: count === 0 ? "0.00" : income,
      netExpense: count === 0 ? "0.00" : expense,
      surplus: count === 0 ? "0.00" : surplus,
    },
    categories: {
      month,
      type,
      items: count === 0 ? [] : [
        {
          kind: "CATEGORY",
          categoryId: 201,
          categoryName: "餐饮",
          transactionCount: 1,
          amount: "30.00",
          children: [{ categoryId: 203, categoryName: "午餐", transactionCount: 1, amount: "30.00" }],
        },
        {
          kind: "UNCATEGORIZED",
          categoryId: null,
          categoryName: "未分类",
          transactionCount: 1,
          amount: "5.00",
          children: [],
        },
      ],
    },
    tags: {
      month,
      type,
      overlap: true,
      items: count === 0 ? [] : [
        { kind: "TAG", tagId: 301, tagName: "外卖", transactionCount: 1, amount: "30.00" },
        { kind: "UNTAGGED", tagId: null, tagName: "无标签", transactionCount: 1, amount: "5.00" },
      ],
    },
  };
}

function rawQuery(path: string): Record<string, string> {
  const query = path.slice(path.indexOf("?") + 1);
  const parsed: Record<string, string> = {};
  for (const part of query.split("&")) {
    const separator = part.indexOf("=");
    parsed[part.slice(0, separator)] = part.slice(separator + 1);
  }
  return parsed;
}

function category(categoryId: number, categoryName: string, children: CategoryStatisticsItem["children"]): CategoryStatisticsItem {
  return { kind: "CATEGORY", categoryId, categoryName, transactionCount: 1, amount: "30.00", children };
}

function listBody(businessTime: string): Record<string, unknown> {
  return {
    month: "2026-09",
    items: [{ id: 1, type: "EXPENSE", amount: "5.00", tags: [], businessTime }],
  };
}

function monthly(month: string, count: number, income: string, expense: string, surplus: string): Record<string, unknown> {
  return { month, transactionCount: count, netIncome: income, netExpense: expense, surplus };
}

function categories(month: string, type: StatisticsType): Record<string, unknown> {
  return {
    month,
    type,
    items: [
      {
        kind: "CATEGORY",
        categoryId: 201,
        categoryName: "餐饮",
        transactionCount: 1,
        amount: "30.00",
        children: [{ categoryId: 203, categoryName: "午餐", transactionCount: 1, amount: "30.00" }],
      },
      { kind: "UNCATEGORIZED", categoryName: "未分类", transactionCount: 1, amount: "5.00", children: [] },
    ],
  };
}

function tags(month: string, type: StatisticsType): Record<string, unknown> {
  return {
    month,
    type,
    overlap: true,
    items: [
      { tagId: 301, tagName: "外卖", transactionCount: 1, amount: "30.00" },
      { tagName: "无标签", transactionCount: 1, amount: "5.00" },
    ],
  };
}

function transaction(id: number, businessTime: string): TransactionListItem {
  return { id, type: "EXPENSE", amount: "5.00", displayName: "午餐", category: null, tags: [], businessTime };
}

function unnamed(): TransactionListItem {
  return { ...transaction(9, "2026-09-03T08:00:00+08:00"), displayName: null };
}

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

function apiFor(responder: (request: HttpTransportRequest) => { statusCode: number; data: unknown }) {
  const storage = memoryStorage();
  saveSession(storage, { token: "opaque-token", expiresAt: "2099-01-01T00:00:00Z" });
  return createStatisticsApi(createHttpClient({
    baseUrl: "http://127.0.0.1:8080",
    storage,
    now: () => new Date("2026-09-25T08:00:00Z"),
    onUnauthorized: () => undefined,
    transport: async (request) => responder(request),
  }));
}
