import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";
import type { Account } from "../miniprogram/account/account";
import {
  deleteGroupConfirm,
  hitKey,
  indexFromDelta,
  parseAccountGroupList,
  edgeScrollDelta,
  planAccountDrop,
  planGroupDrop,
  planHome,
  rowShiftUnits,
  sectionExpanded,
  toggleCollapsed,
  transferPickerSections,
  type AccountGroup,
} from "../miniprogram/account/grouping";

const account = (id: number, groupId: number | null, displayOrder: number | null, lifecycle: Account["lifecycle"] = "ACTIVE"): Account => ({
  id,
  name: `账户${id}`,
  kind: "NORMAL",
  currency: "CNY",
  openingBalance: "0.00",
  currentBalance: id === 2 ? "-30.00" : "90.00",
  createdTime: "2026-11-05T10:00:00+08:00",
  lifecycle,
  groupId,
  displayOrder,
});

const group = (id: number, name: string, subtotal: string, activeCount: number, archivedCount = 0): AccountGroup => ({
  id,
  name,
  displayOrder: id,
  subtotal,
  activeCount,
  archivedCount,
});

describe("account grouping", () => {
  it("keeps a flat list when there is no custom group and rejects a missing subtotal", () => {
    assert.deepEqual(planHome([], [account(1, null, null)], []), { grouped: false, sections: [] });
    assert.equal(parseAccountGroupList({ items: [{ id: 1, name: "日常", displayOrder: 1, activeCount: 0, archivedCount: 0 }] }), null);
    assert.equal(parseAccountGroupList({ items: [{ id: 1, name: "未分组", displayOrder: 1, subtotal: "0.00", activeCount: 0, archivedCount: 0 }] }), null);
  });

  it("places ungrouped accounts after groups and hides an empty ungrouped section", () => {
    const home = planHome(
      [group(7, "日常", "60.00", 2, 1), group(8, "备用", "0.00", 0)],
      [account(1, 7, 2), account(2, 7, 1), account(3, null, null)],
      [account(4, 7, 3, "ARCHIVED")],
    );
    assert.equal(home.grouped, true);
    assert.deepEqual(home.sections.map((section) => section.title), ["日常", "备用", "未分组"]);
    assert.equal(home.sections[0]?.subtotal, "60.00");
    assert.deepEqual(home.sections[0]?.accountIds, [2, 1]);
    assert.deepEqual(home.sections[0]?.archivedIds, [4]);
    assert.equal(home.sections[1]?.subtotal, "0.00");
    assert.deepEqual(home.sections[1]?.accountIds, []);
    assert.deepEqual(home.sections[2]?.accountIds, [3]);
    assert.equal(home.sections[2]?.subtotal, null);
  });

  it("describes group deletion without saying accounts are deleted", () => {
    assert.equal(deleteGroupConfirm(2, 0), "该分组下 2 个账户将变为未分组，账户和流水均保留");
    assert.equal(deleteGroupConfirm(1, 1), "该分组下 2 个账户（其中归档 1 个）将变为未分组，账户和流水均保留");
    assert.equal(deleteGroupConfirm(1, 1).includes("删除账户"), false);
  });

  it("plans a within-group reorder and a cross-group drop as one placement", () => {
    const home = planHome(
      [group(7, "日常", "90.00", 1), group(8, "备用", "0.00", 0)],
      [account(1, 7, 1), account(3, null, null)],
      [account(4, 7, 2, "ARCHIVED")],
    );
    assert.equal(planAccountDrop(home.sections, 1, "group:7", "group:7", 0), null);
    assert.deepEqual(planGroupDrop([7, 8], 8, 0), [8, 7]);
    assert.equal(planGroupDrop([7, 8], 7, 0), null);
    assert.deepEqual(planAccountDrop(home.sections, 3, "ungrouped", "group:8", 0), {
      groupId: 8,
      orderedIds: [3],
    });
    assert.deepEqual(planAccountDrop(home.sections, 1, "group:7", "ungrouped", 1), {
      groupId: null,
      orderedIds: [3, 1],
    });
    assert.equal(indexFromDelta(0, 80, 72, 3), 1);
    assert.equal(hitKey([{ key: "group:7", top: 0, bottom: 40 }, { key: "group:8", top: 40, bottom: 80 }], 40), "group:8");
    assert.equal(hitKey([{ key: "group:7", top: 0, bottom: 40 }], 40), null);
  });

  it("keeps sort expansion apart from the home collapse and shifts only the crossed rows", () => {
    assert.equal(sectionExpanded("group:1", ["group:1"], [], false), false);
    assert.equal(sectionExpanded("group:1", ["group:1"], [], true), true);
    assert.equal(sectionExpanded("group:2", [], ["group:2"], true), false);
    assert.deepEqual(toggleCollapsed(["group:1"], "group:1"), []);
    assert.deepEqual([0, 1, 2, 3].map((index) => rowShiftUnits(index, 1, 3)), [0, 0, -1, -1]);
    assert.equal(edgeScrollDelta(20, 800, 80, 28), -28);
    assert.equal(edgeScrollDelta(760, 800, 80, 28), 28);
    assert.equal(edgeScrollDelta(400, 800, 80, 28), 0);
  });

  it("orders the transfer picker by group and display order, and hides archived or the other side", () => {
    const groups = [
      { ...group(1, "现金", "0.00", 2), displayOrder: 1 },
      { ...group(2, "信用卡", "0.00", 1), displayOrder: 2 },
    ];
    const accounts = [
      account(4, 1, 2),
      account(3, 1, 1),
      account(5, 2, 1),
      account(6, null, 1),
      account(7, 1, 3, "ARCHIVED"),
    ];
    const view = transferPickerSections(groups, accounts, 3, 4, "");
    assert.deepEqual(view.sections.map((section) => [section.title, section.accounts.map((item) => item.id)]), [
      ["现金", [3]],
      ["信用卡", [5]],
      ["未分组", [6]],
    ]);
    const searched = transferPickerSections(groups, accounts, 0, 0, "账户5");
    assert.deepEqual(searched.sections.map((section) => section.accounts.map((item) => item.id)), [[5]]);
    assert.deepEqual(transferPickerSections([], accounts, 0, 0, "").sections[0]?.accounts.map((item) => item.id), [3, 5, 6, 4]);
  });

  it("uses the same grip mark as category rows and follows the finger", () => {
    for (const page of ["account-list", "account-group-manage"]) {
      const markup = readFileSync(new URL(`../miniprogram/pages/${page}/index.wxml`, import.meta.url), "utf8");
      const script = readFileSync(new URL(`../miniprogram/pages/${page}/index.ts`, import.meta.url), "utf8");
      assert.equal(markup.includes("手柄"), false);
      assert.equal(markup.includes("⋮⋮"), true);
      assert.equal(markup.includes("translateY"), true);
      assert.equal(script.includes("dragOffset"), true);
    }
  });
});
