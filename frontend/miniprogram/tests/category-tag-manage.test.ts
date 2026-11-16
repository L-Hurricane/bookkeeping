import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";
import { parseCategoryList, type CategoryNode } from "../miniprogram/category/category";
import { deletionConfirm, keptSelection, manageRows, measuredRows, normalizedName, parentChoices, planParentDrop, rowHitId, type NamedNode } from "../miniprogram/manage/tree";
import { createCategoryApi } from "../miniprogram/services/category";
import { createTagApi } from "../miniprogram/services/tag";
import { flattenTagTree, parseTagTree } from "../miniprogram/tag/tag";
import { categoryLabel, categoryRows, toggleTagSelection } from "../miniprogram/transaction/reference";
import { createHttpClient, type HttpTransportRequest } from "../miniprogram/http/client";
import { saveSession } from "../miniprogram/session/session";
import type { KeyValueStorage } from "../miniprogram/storage/key-value";

const tree: CategoryNode[] = [
  {
    id: 1,
    name: "餐饮",
    type: "EXPENSE",
    bookable: false,
    children: [
      {
        id: 2,
        name: "午餐",
        type: "EXPENSE",
        bookable: false,
        children: [{ id: 3, name: "公司", type: "EXPENSE", bookable: true, children: [] }],
      },
    ],
  },
];

describe("three-level category and shared tag selection", () => {
  it("offers only the current leaf and keeps its full path", () => {
    const rows = categoryRows(tree);
    assert.deepEqual(rows.map((row) => [row.kind, row.id, row.label]), [
      ["group", null, "餐饮"],
      ["group", null, "午餐"],
      ["option", 3, "公司"],
    ]);
    assert.equal(categoryLabel(tree, 3), "餐饮 / 午餐 / 公司");
    assert.equal(categoryLabel(tree, 2), "未分类");
  });

  it("flattens a tag tree without adding or removing either level", () => {
    const tags = flattenTagTree({
      items: [{
        id: 1,
        name: "饮食",
        type: "EXPENSE",
        children: [{ id: 2, name: "外卖", type: "EXPENSE", children: [] }],
      }],
    });
    assert.deepEqual(tags?.map((tag) => tag.id), [1, 2]);
    const parentOnly = toggleTagSelection([], 1, tags ?? []);
    assert.deepEqual(parentOnly, [1]);
    const both = toggleTagSelection(parentOnly, 2, tags ?? []);
    assert.deepEqual(both, [1, 2]);
    assert.deepEqual(keptSelection([1, 2]), [1, 2]);
    assert.equal(parseTagTree({ items: [{ id: 1, name: "过深", type: "EXPENSE", children: [{ id: 2, name: "二", type: "EXPENSE", children: [{ id: 3, name: "三", type: "EXPENSE", children: [{ id: 4, name: "四", type: "EXPENSE" }] }] }] }] }), null);
  });

  it("limits parents to three levels and describes a leaf deletion", () => {
    assert.deepEqual(manageRows(tree).map((row) => [row.name, row.depth, row.hasChildren]), [
      ["餐饮", 1, true],
      ["午餐", 2, true],
      ["公司", 3, false],
    ]);
    assert.deepEqual(parentChoices(tree, 3).map((choice) => choice.id), [null, 1, 2]);
    assert.deepEqual(parentChoices(tree, 1).map((choice) => choice.id), [null]);
    assert.equal(normalizedName("  外卖  "), "外卖");
    assert.equal(normalizedName("   "), null);
    assert.equal(deletionConfirm("分类", "公司", 2), "删除「公司」后，2 笔交易会变为未分类。交易、金额和余额保持不变。");
    assert.equal(deletionConfirm("标签", "外卖", 2), "删除「外卖」后，会移除它在 2 笔交易上的关联。交易本身保持不变。");
  });

  it("resolves a drag by the measured row under the finger", () => {
    const rows = measuredRows([[
      { dataset: { id: "11" }, top: 0, bottom: 40 },
      { dataset: { id: 12 }, top: 40, bottom: 80 },
      { dataset: {}, top: 80, bottom: 120 },
    ]]);
    assert.deepEqual(rows, [
      { id: 11, top: 0, bottom: 40 },
      { id: 12, top: 40, bottom: 80 },
    ]);
    assert.equal(rowHitId(rows, 50), 12);
    assert.equal(rowHitId(rows, 80), 12);
    assert.equal(rowHitId(rows, 81), null);
    assert.deepEqual(measuredRows(undefined), []);
  });

  it("binds every queried tag row to its own id and moves only onto a legal parent", () => {
    const tagMarkup = readFileSync(new URL("../miniprogram/pages/tag-manage/index.wxml", import.meta.url), "utf8");
    const tagSource = readFileSync(new URL("../miniprogram/pages/tag-manage/index.ts", import.meta.url), "utf8");
    const categoryMarkup = readFileSync(new URL("../miniprogram/pages/category-manage/index.wxml", import.meta.url), "utf8");
    const categorySource = readFileSync(new URL("../miniprogram/pages/category-manage/index.ts", import.meta.url), "utf8");
    assert.equal(rowRepeatDataId(tagMarkup), "{{item.id}}");
    assert.equal(rowRepeatDataId(categoryMarkup), "{{item.id}}");

    const tags: NamedNode[] = [
      {
        id: 1,
        name: "旅行",
        children: [{ id: 2, name: "城市", children: [{ id: 3, name: "北京", children: [] }] }],
      },
      { id: 4, name: "饮食", children: [] },
    ];
    const rows = manageRows(tags);
    assert.deepEqual(rows.map((row) => [row.id, row.depth]), [[1, 1], [2, 2], [3, 3], [4, 1]]);
    const measured = measuredRows([rows.map((row, index) => ({
      dataset: { id: row.id },
      top: index * 40,
      bottom: index * 40 + 40,
    }))]);
    assert.deepEqual(measured.map((row) => row.id), [1, 2, 3, 4]);
    assert.deepEqual(planParentDrop(tags, 3, measured, 130), { status: "move", parentId: 4 });
    assert.equal(planParentDrop(tags, 1, measured, 90).status, "rejected");
    assert.equal(planParentDrop(tags, 3, measured, 90).status, "unchanged");
    assert.equal(planParentDrop(tags, 3, measuredRows([[{ top: 0, bottom: 40 }]]), 10).status, "unmeasured");
    assert.equal(tagSource.includes("tagApi.moveTag(dragId, \"EXPENSE\", decision.parentId)"), true);
    assert.equal(tagSource.includes("decision.status === \"rejected\""), true);
    assert.equal(categorySource.includes("categoryApi.moveCategory(dragId, targetId)"), true);
    assert.equal(categorySource.includes("不能移动到这个上级"), true);
    assert.equal(parentChoices(tags, 1).some((choice) => choice.id === 3), false);
  });

  it("keeps category and tag drag on the page query that returns rectangles", () => {
    for (const page of ["category-manage", "tag-manage"]) {
      const source = readFileSync(new URL(`../miniprogram/pages/${page}/index.ts`, import.meta.url), "utf8");
      assert.equal(source.includes("createSelectorQuery().selectAll(\".row\").boundingClientRect().exec"), true);
      assert.equal(source.includes("boundingClientRect(("), false);
      assert.equal(source.includes(".in(this)"), false);
    }
  });

  it("sends category and tag management requests", async () => {
    const requests: HttpTransportRequest[] = [];
    const http = client((request) => {
      requests.push(request);
      if (request.url.includes("deletion-preview")) {
        return { statusCode: 200, data: { directTransactionCount: 2 } };
      }
      if (request.url.endsWith("/deletion")) {
        return { statusCode: 204, data: null };
      }
      if (request.method === "GET") {
        return { statusCode: 200, data: { items: tree } };
      }
      return { statusCode: 200, data: tree[0] };
    });
    const categories = createCategoryApi(http);
    assert.equal((await categories.categoryDeletionPreview(3)), 2);
    await categories.deleteCategory(3, 2);
    await categories.renameCategory(3, "食堂");
    await categories.moveCategory(3, 2);
    const tags = createTagApi(http);
    const listed = await tags.listTags("EXPENSE");
    assert.deepEqual(listed.map((tag) => tag.name), ["餐饮", "午餐", "公司"]);
    assert.equal(requests.some((request) => request.url.endsWith("/api/v1/categories/3/deletion") && request.data !== undefined), true);
    assert.equal(parseCategoryList({ items: tree })?.[0]?.children[0]?.children[0]?.bookable, true);
  });
});

function rowRepeatDataId(markup: string): string | null {
  const views = markup.match(/<view\b[^>]*>/g) ?? [];
  const row = views.find((tag) => /\bwx:for="\{\{rows\}\}"/.test(tag) && /\bclass="row[\s"]/.test(tag));
  return row?.match(/\bdata-id="([^"]*)"/)?.[1] ?? null;
}

function client(handler: (request: HttpTransportRequest) => { statusCode: number; data: unknown }): ReturnType<typeof createHttpClient> {
  let value: string | undefined;
  const storage: KeyValueStorage = {
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
  saveSession(storage, { token: "opaque-token", expiresAt: "2099-01-01T00:00:00Z" });
  return createHttpClient({
    baseUrl: "http://127.0.0.1:8080",
    transport: async (request) => handler(request),
    storage,
    now: () => new Date("2026-10-25T00:00:00Z"),
    onUnauthorized: () => undefined,
  });
}
