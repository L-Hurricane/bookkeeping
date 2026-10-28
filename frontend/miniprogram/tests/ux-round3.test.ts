import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { afterEach, describe, it } from "node:test";
import { accountChoiceGroups, resetStatisticsSelection, retainKnownAccountIds, rememberStatisticsSelection, statisticsSelection } from "../miniprogram/statistics/account-filter";
import { appendCreatedTag, tagPickerRows, tagSelectionSummary } from "../miniprogram/transaction/tag-picker";
import type { TagNode } from "../miniprogram/tag/tag";

const home = readFileSync(new URL("../miniprogram/pages/account-home/index.wxml", import.meta.url), "utf8");
const accountHomeTs = readFileSync(new URL("../miniprogram/pages/account-home/index.ts", import.meta.url), "utf8");
const list = readFileSync(new URL("../miniprogram/pages/account-list/index.wxml", import.meta.url), "utf8");
const feed = readFileSync(new URL("../miniprogram/pages/all-transactions/index.wxml", import.meta.url), "utf8");
const fab = readFileSync(new URL("../miniprogram/components/entry-fab/index.wxml", import.meta.url), "utf8");
const detail = readFileSync(new URL("../miniprogram/pages/transaction-detail/index.ts", import.meta.url), "utf8");
const detailView = readFileSync(new URL("../miniprogram/pages/transaction-detail/index.wxml", import.meta.url), "utf8");
const createView = readFileSync(new URL("../miniprogram/pages/transaction-create/index.wxml", import.meta.url), "utf8");
const tagSheet = readFileSync(new URL("../miniprogram/transaction/tag-sheet.wxml", import.meta.url), "utf8");
const report = readFileSync(new URL("../miniprogram/pages/statistics-report/index.wxml", import.meta.url), "utf8");

const tree: TagNode[] = [{
  id: 1,
  name: "家庭",
  type: "EXPENSE",
  children: [{ id: 2, name: "孩子", type: "EXPENSE", children: [] }],
}];

describe("s6 fb-016 to fb-019", () => {
  afterEach(() => {
    resetStatisticsSelection();
  });

  it("keeps four account actions apart from the three shared actions", () => {
    assert.equal(fab.includes("支出"), true);
    assert.equal(fab.includes("收入"), true);
    assert.equal(fab.includes("转账"), true);
    assert.equal(fab.includes("showAdjust"), true);
    assert.equal(fab.includes("调整余额"), true);
    assert.equal(home.includes("show-adjust"), true);
    assert.equal(home.includes(">余额调整<"), false);
    assert.equal(home.includes(">统计<"), false);
    assert.equal(home.includes("{{kindLabel}}"), false);
    assert.equal(list.includes("show-adjust"), false);
    assert.equal(feed.includes("show-adjust"), false);
  });

  it("selects a parent and child explicitly and keeps a cancelled sheet off the summary", () => {
    const rows = tagPickerRows(tree, [2], "");
    assert.equal(rows.map((row) => row.name).join(","), "家庭,孩子");
    assert.equal(rows[0]?.selected, false);
    assert.equal(rows[1]?.selected, true);
    assert.equal(tagPickerRows(tree, [1], "孩子")[0]?.selected, true);
    assert.equal(tagSelectionSummary([2], [{ id: 2, name: "孩子" }]), "孩子");
    assert.equal(tagSelectionSummary([], tree), "未选择");
    assert.equal(appendCreatedTag(tree, { id: 9, name: "新标签", type: "EXPENSE", children: [] }).length, 2);
    assert.equal(detailView.includes("编辑交易"), true);
    assert.equal(detailView.includes("tagSheet"), true);
    assert.equal(createView.includes("tagSheet"), true);
    assert.equal(tagSheet.includes("选择标签"), true);
    assert.equal(tagSheet.includes("新建标签"), true);
    assert.equal(detail.includes("this.onEdit();"), false);
    const capture = accountHomeTs.slice(
      accountHomeTs.indexOf("captureTransactionUpdate(result: unknown)"),
      accountHomeTs.indexOf("receiveAdjustmentCreated(payload: unknown)"),
    );
    assert.equal(capture.includes("redirectIfForeignAccount"), false);
    assert.equal(capture.includes("noteListStale"), true);
    assert.equal(accountHomeTs.includes("receiveTransactionCreated"), true);
    assert.equal(detail.includes("wx.navigateBack();"), true);
  });

  it("keeps the three statistics modes exclusive and does not treat an empty custom set as every account", () => {
    const groups = accountChoiceGroups([
      { id: 1, name: "银行", archived: false },
      { id: 2, name: "旧卡", archived: true },
    ], [2], "", { archived: true });
    assert.deepEqual(groups.map((group) => group.key), ["active", "archived"]);
    assert.equal(groups[1]?.collapsed, true);
    assert.equal(groups[1]?.accounts[0]?.checked, true);
    assert.equal(accountChoiceGroups([{ id: 1, name: "银行", archived: false }], [], "没有").length, 0);
    assert.deepEqual(retainKnownAccountIds([1, 9], [1]), [1]);
    rememberStatisticsSelection({ preset: "YEAR", customFrom: "", customTo: "", scope: "SELECTED", accountIds: [] });
    const saved = statisticsSelection();
    assert.equal(saved.scope, "SELECTED");
    assert.equal(saved.accountIds.length, 0);
    assert.equal(saved.preset, "YEAR");
    assert.equal(report.includes("未归档账户"), true);
    assert.equal(report.includes("全部账户（含已归档）"), true);
    assert.equal(report.includes("已选 {{selectedCount}} 个账户"), true);
    assert.equal(report.includes("不含归档"), false);
  });
});
