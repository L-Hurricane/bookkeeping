import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";
import { createHttpClient, type HttpTransportRequest } from "../miniprogram/http/client";
import { saveSession } from "../miniprogram/session/session";
import { createLedgerStatisticsApi } from "../miniprogram/services/ledger-statistics";
import {
  parseLedgerDetailContext,
  parseLedgerReport,
  parseLedgerTransactions,
  statisticsDetailPagePath,
  statisticsReportPath,
  type LedgerDrill,
  type LedgerReport,
} from "../miniprogram/statistics/ledger-contract";
import { ledgerStatisticsEntryPath } from "../miniprogram/statistics/drilldown";
import { flowRows } from "../miniprogram/statistics/ledger-view";
import type { KeyValueStorage } from "../miniprogram/storage/key-value";

describe("ledger statistics", () => {
  it("shows a statistics entry on the ledger home and returns by the existing page stack", () => {
    const page = readFileSync(new URL("../miniprogram/pages/account-list/index.wxml", import.meta.url), "utf8");
    const source = readFileSync(new URL("../miniprogram/pages/account-list/index.ts", import.meta.url), "utf8");
    assert.match(page, /bindtap="onOpenStatistics">统计<\/button>/);
    assert.match(source, /wx\.navigateTo\(\{ url: ledgerStatisticsEntryPath\(\) \}\)/);
    assert.equal(ledgerStatisticsEntryPath(), "/pages/statistics/index");
    assert.equal(page.includes("tabBar"), false);
  });

  it("requests the Beijing preset and selected accounts without using a JavaScript amount", async () => {
    const requests: string[] = [];
    const api = createLedgerStatisticsApi(client((request) => {
      requests.push(request.url);
      return { statusCode: 200, data: report() };
    }));
    const loaded = await api.load({ preset: "MONTH", accountScope: "SELECTED", accountIds: [3, 8] });
    assert.equal(requests[0], "https://api.bookkeeping.local/api/v1/statistics?preset=MONTH&accountScope=SELECTED&accountIds=3,8");
    assert.equal(loaded.expense.net, "-1000.00");
    assert.equal(loaded.netWorth.change, "80.00");
  });

  it("keeps a signed parent total and omits a ratio when the net is zero", () => {
    const rows = flowRows({
      net: "0.00",
      categories: [{
        id: 1,
        name: "餐饮",
        transactionCount: 2,
        amount: "0.00",
        share: null,
        children: [{ id: 2, name: "早餐", transactionCount: 2, amount: "0.00", share: null, children: [] }],
      }],
      tags: [],
      uncategorized: null,
      untagged: null,
    }, "category");
    assert.equal(rows[0]?.detail, "2 笔 · 不显示占比");
    assert.equal(rows[1]?.title, "早餐");
    assert.equal(rows[1]?.depth, 1);
  });

  it("opens a tag detail on the same filters and can switch to the direct association", () => {
    const path = statisticsDetailPagePath({
      query: { preset: "YEAR", accountScope: "ACTIVE" },
      type: "EXPENSE",
      drill: { kind: "tag", tagId: 9, title: "旅行", tagMode: "TREE" },
    });
    const query = Object.fromEntries(path.split("?")[1]?.split("&").map((part) => part.split("=")) ?? []);
    const context = parseLedgerDetailContext(query);
    assert.equal(context?.drill.kind, "tag");
    if (context?.drill.kind === "tag") {
      assert.equal(context.drill.tagMode, "TREE");
      assert.equal(context.drill.title, "旅行");
    }
    assert.equal(statisticsReportPath({ preset: "CUSTOM", from: "2026-01-01", to: "2026-01-31", accountScope: "ALL" }),
      "/api/v1/statistics?preset=CUSTOM&accountScope=ALL&from=2026-01-01&to=2026-01-31");
    const selected = statisticsDetailPagePath({
      query: { preset: "MONTH", accountScope: "ACTIVE" },
      type: "EXPENSE",
      drill: { kind: "tags", tagIds: [9, 4], title: "所选标签" },
    });
    assert.match(selected, /tagIds=9,4/);
  });

  it("decodes Chinese and special detail titles once", () => {
    assert.equal(detailTitle({ kind: "category", categoryId: 2, title: "早餐" }), "早餐");
    assert.equal(detailTitle({ kind: "uncategorized", title: "未分类" }), "未分类");
    assert.equal(detailTitle({ kind: "untagged", title: "无标签" }), "无标签");
    assert.equal(detailTitle({ kind: "tag", tagId: 9, title: "A & 100% + 空格", tagMode: "TREE" }), "A & 100% + 空格");
    const broken = rawDetailQuery(statisticsDetailPagePath({
      query: { preset: "MONTH", accountScope: "ACTIVE" },
      type: "EXPENSE",
      drill: { kind: "category", categoryId: 2, title: "早餐" },
    }));
    broken.title = "%E5%A4";
    assert.equal(parseLedgerDetailContext(broken), null);
    broken.title = "%";
    assert.equal(parseLedgerDetailContext(broken), null);
  });

  it("accepts a payload that omits null shares, empty slices, and an unnamed transaction", () => {
    const body = report();
    const wire = {
      ...body,
      expense: {
        net: "0.00",
        categories: [{ id: 1, name: "餐饮", transactionCount: 2, amount: "0.00", children: [] }],
        tags: [],
      },
    };
    const parsed = parseLedgerReport(wire);
    assert.equal(parsed?.expense.categories[0]?.share, null);
    assert.equal(parsed?.expense.uncategorized, null);
    assert.equal(parsed?.expense.untagged, null);
    const list = {
      items: [{
        id: 7,
        accountId: 3,
        accountCreatedTime: "2026-08-01T00:00:00+08:00",
        type: "EXPENSE",
        amount: "400.00",
        businessTime: "2026-10-02T12:00:00+08:00",
      }],
    };
    assert.equal(parseLedgerTransactions(list)?.items[0]?.displayName, null);
  });

  it("rejects a report that puts a number in a money field", () => {
    const body = report();
    const invalid = { ...body, netWorth: { ...body.netWorth, opening: 10 } };
    assert.equal(parseLedgerReport(invalid), null);
  });
});

function detailTitle(drill: LedgerDrill): string | undefined {
  return parseLedgerDetailContext(rawDetailQuery(statisticsDetailPagePath({
    query: { preset: "MONTH", accountScope: "ACTIVE" },
    type: "EXPENSE",
    drill,
  })))?.drill.title;
}

function rawDetailQuery(path: string): Record<string, string> {
  const parsed: Record<string, string> = {};
  for (const part of path.slice(path.indexOf("?") + 1).split("&")) {
    const separator = part.indexOf("=");
    parsed[part.slice(0, separator)] = part.slice(separator + 1);
  }
  return parsed;
}

function report(): LedgerReport {
  return {
    preset: "MONTH",
    from: "2026-10-01",
    to: "2026-10-31",
    accountScope: "SELECTED",
    accounts: [{ id: 3, name: "现金", lifecycle: "ACTIVE" }],
    netWorth: { opening: "20.00", closing: "100.00", change: "80.00", points: [{ date: "2026-10-31", amount: "100.00" }] },
    income: { net: "0.00", categories: [], tags: [], uncategorized: null, untagged: null },
    expense: {
      net: "-1000.00",
      categories: [{
        id: 1,
        name: "餐饮",
        transactionCount: 1,
        amount: "-400.00",
        share: "-40.00",
        children: [],
      }],
      tags: [],
      uncategorized: { transactionCount: 1, amount: "-300.00", share: "-30.00" },
      untagged: null,
    },
  };
}

function client(handler: (request: HttpTransportRequest) => { statusCode: number; data: unknown }) {
  let value: string | undefined;
  const storage: KeyValueStorage = {
    getItem: () => value,
    setItem: (_key, next) => { value = next; },
    removeItem: () => { value = undefined; },
  };
  saveSession(storage, { token: "opaque-token", expiresAt: "2099-01-01T00:00:00Z" });
  return createHttpClient({
    baseUrl: "https://api.bookkeeping.local",
    transport: (request) => Promise.resolve(handler(request)),
    storage,
    now: () => new Date("2026-10-26T02:00:00Z"),
    onUnauthorized: () => undefined,
  });
}
