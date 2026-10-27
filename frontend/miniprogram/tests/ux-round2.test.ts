import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";
import { groupByTimeLabel } from "../miniprogram/ledger/day-groups";
import { parentChoices } from "../miniprogram/manage/tree";
import { lineYs } from "../miniprogram/statistics/line-layout";
import { entryChooserPath } from "../miniprogram/entry/fab";

const accountHome = readFileSync(new URL("../miniprogram/pages/account-home/index.wxml", import.meta.url), "utf8");
const mine = readFileSync(new URL("../miniprogram/pages/mine/index.wxml", import.meta.url), "utf8");
const report = readFileSync(new URL("../miniprogram/pages/statistics-report/index.wxml", import.meta.url), "utf8");
const createForm = readFileSync(new URL("../miniprogram/pages/transaction-create/index.wxml", import.meta.url), "utf8");
const editForm = readFileSync(new URL("../miniprogram/pages/transaction-detail/index.wxml", import.meta.url), "utf8");

describe("s6 ux round 2", () => {
  it("draws the server point order without inventing a flat day", () => {
    const ys = lineYs(["10.00", "10.00", "8.00"], 100);
    assert.equal(ys[0], ys[1]);
    assert.ok((ys[2] ?? 0) > (ys[0] ?? 0));
    assert.equal(lineYs([], 100).length, 0);
  });

  it("groups one month by business day and keeps a root movable under a sibling", () => {
    const groups = groupByTimeLabel([
      { dayKey: "2026-10-27" },
      { dayKey: "2026-10-27" },
      { dayKey: "2024-10-27" },
    ]);
    assert.equal(groups[0]?.label, "2026年10月27日 周二");
    assert.equal(groups[0]?.count, 2);
    assert.equal(groups[1]?.key, "2024-10-27");
    assert.equal(groups[1]?.label, "2024年10月27日 周日");
    const choices = parentChoices([
      { id: 1, name: "餐饮", children: [{ id: 2, name: "早餐", children: [] }] },
      { id: 3, name: "交通", children: [] },
    ], 3);
    assert.equal(choices.some((choice) => choice.id === 1), true);
    assert.equal(choices.some((choice) => choice.id === 3), false);
  });

  it("keeps logout on mine and off the account ledger", () => {
    assert.equal(mine.includes("退出登录"), true);
    assert.equal(accountHome.includes("退出登录"), false);
    assert.equal(report.includes("net-line"), true);
    assert.equal(report.includes("trend-bar"), false);
    assert.equal(entryChooserPath("EXPENSE").includes("choose=1"), true);
  });

  it("uses the same sheet language on create and edit, and keeps the statistics filters visible", () => {
    assert.equal(createForm.includes("选择分类"), true);
    assert.equal(editForm.includes("选择分类"), true);
    assert.equal(editForm.includes("选择资金账户"), true);
    assert.equal(editForm.includes("可以同时选择上级和下级"), false);
    assert.equal(report.includes("日期范围"), true);
    assert.equal(report.includes("账户范围"), true);
    assert.equal(report.includes("更多"), false);
  });
});
