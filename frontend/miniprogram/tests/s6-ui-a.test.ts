import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";
import { entryPath } from "../miniprogram/entry/fab";
import { ledgerFeedPath, parseLedgerFeed } from "../miniprogram/ledger/feed-contract";
import { beijingDate, monthLastDate } from "../miniprogram/ledger/month";
import { pointHeights, ringSegments } from "../miniprogram/statistics/ledger-view";
import type { LedgerFlow } from "../miniprogram/statistics/ledger-contract";

const home = readFileSync(new URL("../miniprogram/pages/account-list/index.wxml", import.meta.url), "utf8");
const statistics = readFileSync(new URL("../miniprogram/pages/statistics/index.wxml", import.meta.url), "utf8");
const feed = readFileSync(new URL("../miniprogram/pages/all-transactions/index.wxml", import.meta.url), "utf8");

describe("s6 approved ui", () => {
  it("puts the month summary and all-transactions entry on the ledger home", () => {
    assert.equal(home.includes("本月账本净值"), true);
    assert.equal(home.includes("全部交易"), true);
    assert.equal(home.includes("最近交易"), false);
  });

  it("keeps rolling windows distinct from the natural month and does not load period rows on the statistics home", () => {
    assert.equal(statistics.includes("近1月"), true);
    assert.equal(statistics.includes("近3月"), true);
    assert.equal(statistics.includes("近1年"), true);
    assert.equal(statistics.includes("查看期间交易"), true);
    assert.equal(statistics.includes("包含归档"), true);
  });

  it("does not show a per-row balance on the cross-account feed", () => {
    assert.equal(feed.includes("不显示逐笔余额"), true);
    assert.equal(feed.includes("余额调整不计入"), true);
  });

  it("opens income on the same create form and transfer on its own form", () => {
    const account = { id: 7, createdTime: "2026-01-01T00:00:00+08:00" };
    assert.equal(entryPath("EXPENSE", account).includes("type=INCOME"), false);
    assert.equal(entryPath("INCOME", account).includes("type=INCOME"), true);
    assert.equal(entryPath("TRANSFER", account).includes("/pages/transfer-create/"), true);
  });

  it("parses a paged feed and keeps the cursor opaque", () => {
    const page = parseLedgerFeed({
      label: "2026-10",
      summary: { netIncome: "10.00", netExpense: "3.00", surplus: "7.00" },
      items: [{
        kind: "TRANSFER",
        id: 4,
        transactionType: null,
        displayName: "转账",
        amount: "-2.00",
        businessTime: "2026-10-02T09:00:00+08:00",
        accountId: 3,
        accountName: "现金",
        accountCreatedTime: "2026-01-01T00:00:00+08:00",
      }],
      nextCursor: "2026-10-02T09:00:00|1|2026-10-02T01:00:00|TRANSFER|3|4",
    });
    assert.equal(page?.items[0]?.amount, "-2.00");
    assert.equal(page?.nextCursor?.includes("TRANSFER"), true);
    assert.equal(ledgerFeedPath({
      accountScope: "ACTIVE",
      month: "2026-10",
      cursor: page?.nextCursor ?? undefined,
    }).includes("cursor="), true);
  });

  it("refuses a ring when a category net is negative and scales existing points", () => {
    const flow: LedgerFlow = {
      net: "10.00",
      categories: [{
        id: 1, name: "餐饮", transactionCount: 1, amount: "-1.00", share: "-10.00", children: [],
      }],
      tags: [],
      uncategorized: null,
      untagged: null,
    };
    assert.equal(ringSegments(flow), null);
    assert.deepEqual(pointHeights(["0.00", "10.00"]), [8, 80]);
  });

  it("uses the Beijing calendar day for the current-month cutoff", () => {
    assert.equal(beijingDate(new Date("2026-10-26T16:30:00Z")), "2026-10-27");
    assert.equal(monthLastDate("2026-02"), "2026-02-28");
  });
});
