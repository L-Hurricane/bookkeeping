import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";
import { entryChooserPath, entryPath } from "../miniprogram/entry/fab";
import { ledgerFeedPath, parseLedgerFeed } from "../miniprogram/ledger/feed-contract";
import { beijingDate, monthLastDate } from "../miniprogram/ledger/month";
import { pointHeights, ringHit, ringSegments, ringTouchPoint } from "../miniprogram/statistics/ledger-view";
import type { LedgerFlow } from "../miniprogram/statistics/ledger-contract";

const home = readFileSync(new URL("../miniprogram/pages/account-list/index.wxml", import.meta.url), "utf8");
const statistics = readFileSync(new URL("../miniprogram/pages/statistics/index.wxml", import.meta.url), "utf8");
const report = readFileSync(new URL("../miniprogram/pages/statistics-report/index.wxml", import.meta.url), "utf8");
const feed = readFileSync(new URL("../miniprogram/pages/all-transactions/index.wxml", import.meta.url), "utf8");

describe("s6 approved ui", () => {
  it("puts the month summary and all-transactions entry on the ledger home", () => {
    assert.equal(home.includes("本月账本净值"), true);
    assert.equal(home.includes("{{netTone}}"), true);
    assert.equal(feed.includes("{{surplusTone}}"), true);
    assert.equal(home.includes("全部交易"), true);
    assert.equal(home.includes("最近交易"), false);
    assert.equal(home.includes("退出登录"), false);
  });

  it("keeps rolling windows on the statistics report and leaves the statistics home as a catalog", () => {
    assert.equal(statistics.includes("净值趋势"), true);
    assert.equal(statistics.includes("近1月"), false);
    assert.equal(report.includes("近1月"), true);
    assert.equal(report.includes("近3月"), true);
    assert.equal(report.includes("近1年"), true);
    assert.equal(report.includes("查看期间交易"), true);
    assert.equal(report.includes("全部账户（含已归档）"), true);
    assert.equal(report.includes('bindtap="onRingTap"'), true);
    assert.equal(report.includes('catchtap="onPickTagBar"'), true);
    assert.equal(report.includes("占比不必合计为 100%"), true);
    assert.equal(report.includes("加入多选"), false);
    assert.equal(report.includes("查看所选标签"), false);
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
    assert.equal(entryChooserPath("EXPENSE"), "/pages/transaction-create/index?choose=1");
    assert.equal(entryChooserPath("INCOME").includes("type=INCOME"), true);
    assert.equal(entryChooserPath("TRANSFER"), "/pages/transfer-create/index?choose=1");
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

  it("identifies a ring sector from the backend share without inventing an amount", () => {
    const flow: LedgerFlow = {
      net: "80.00",
      categories: [
        { id: 3, name: "餐饮", transactionCount: 2, amount: "40.00", share: "50.00", children: [] },
        { id: 4, name: "交通", transactionCount: 1, amount: "40.00", share: "50.00", children: [] },
      ],
      tags: [],
      uncategorized: null,
      untagged: null,
    };
    const segments = ringSegments(flow);
    assert.equal(segments?.[0]?.key, "category-3");
    assert.equal(segments?.[0]?.amountLabel, "¥40.00");
    assert.equal(segments?.[0]?.share, "50.00");
    assert.deepEqual(ringHit(segments ?? [], 175, 100, 200, 200), { kind: "sector", index: 0 });
    assert.deepEqual(ringHit(segments ?? [], 25, 100, 200, 200), { kind: "sector", index: 1 });
    assert.deepEqual(ringHit(segments ?? [], 100, 100, 200, 200), { kind: "hole" });
    assert.deepEqual(ringHit(segments ?? [], 1, 1, 200, 200), { kind: "miss" });
    const partial = ringSegments({
      ...flow,
      categories: [{ id: 3, name: "餐饮", transactionCount: 1, amount: "20.00", share: "25.00", children: [] }],
    });
    assert.equal(ringHit(partial ?? [], 25, 100, 200, 200).kind, "miss");
    assert.deepEqual(ringTouchPoint(undefined, undefined, undefined, { width: 200, height: 200 }), null);
    assert.deepEqual(ringTouchPoint(10, 12, undefined, { width: 200, height: 200 }), { x: 10, y: 12 });
  });

  it("uses the Beijing calendar day for the current-month cutoff", () => {
    assert.equal(beijingDate(new Date("2026-10-26T16:30:00Z")), "2026-10-27");
    assert.equal(monthLastDate("2026-02"), "2026-02-28");
  });
});
