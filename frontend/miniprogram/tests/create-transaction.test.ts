import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { bookableCategoryOptions, parseCategoryList } from "../miniprogram/category/category";
import { parseTag, parseTagList } from "../miniprogram/tag/tag";
import {
  applyBusinessTimeDraft,
  businessMonth,
  currentBeijingBusinessTime,
  isBusinessTimeAllowed,
  normalizeBusinessDateInput,
  normalizeBusinessTimeInput,
  validateBusinessTime,
} from "../miniprogram/transaction/business-time";
import {
  buildCreateTransactionSnapshot,
  createTransactionForm,
  createTransactionRequestBody,
  postSaveResult,
  switchTransactionType,
  validateTransactionAmount,
  type CreateTransactionForm,
} from "../miniprogram/transaction/form";
import { parseTransactionResponse } from "../miniprogram/transaction/transaction";

const transaction = {
  id: 4001,
  accountId: 1001,
  type: "EXPENSE",
  amount: "-20.00",
  name: "未分类支出",
  displayName: "未分类支出",
  category: { id: 203, name: "其他" },
  tags: [{ id: 301, name: "外卖" }],
  businessTime: "2026-09-12T20:00:00+08:00",
  remark: "备注",
};

describe("transaction response", () => {
  it("parses a populated transaction and nullable omitted fields", () => {
    const parsed = parseTransactionResponse(transaction);
    assert.equal(parsed?.type, "EXPENSE");
    assert.equal(parsed?.amount, "-20.00");
    assert.equal(parsed?.category?.name, "其他");
    const { name, displayName, category, remark, ...rest } = transaction;
    const omitted = parseTransactionResponse({ ...rest, tags: [] });
    assert.equal(omitted?.name, null);
    assert.equal(omitted?.displayName, null);
    assert.equal(omitted?.category, null);
    assert.equal(omitted?.remark, null);
    assert.deepEqual(omitted?.tags, []);
    assert.equal(name.length > 0 && displayName.length > 0 && category.id === 203 && remark.length > 0, true);
  });

  it("rejects missing tags and malformed fields", () => {
    const { tags, ...missingTags } = transaction;
    assert.equal(parseTransactionResponse(missingTags), null);
    assert.equal(tags.length, 1);
    assert.equal(parseTransactionResponse({ ...transaction, id: 0 }), null);
    assert.equal(parseTransactionResponse({ ...transaction, accountId: -1 }), null);
    assert.equal(parseTransactionResponse({ ...transaction, type: "TRANSFER" }), null);
    assert.equal(parseTransactionResponse({ ...transaction, amount: "20" }), null);
    assert.equal(parseTransactionResponse({ ...transaction, businessTime: "2026-09-12T20:00:00Z" }), null);
    assert.equal(parseTransactionResponse({ ...transaction, category: { id: 1 } }), null);
    assert.equal(parseTransactionResponse({ ...transaction, tags: [{ id: 1, name: "" }] }), null);
  });
});

describe("category and tag responses", () => {
  it("keeps bookable flags and parent-child structure", () => {
    const categories = parseCategoryList({
      items: [
        { id: 210, name: "购物", type: "EXPENSE", bookable: true, children: [] },
        {
          id: 201,
          name: "餐饮",
          type: "EXPENSE",
          bookable: false,
          children: [
            { id: 202, name: "早餐", type: "EXPENSE", bookable: true, children: [] },
            { id: 204, name: "分组", type: "EXPENSE", bookable: false, children: [] },
          ],
        },
      ],
    });
    assert.equal(categories?.[0]?.bookable, true);
    const options = bookableCategoryOptions(categories ?? []);
    assert.equal(options[1]?.name, "餐饮");
    assert.equal(options[1]?.bookable, false);
    assert.deepEqual(options[1]?.children.map((child) => child.name), ["早餐"]);
    assert.equal(parseCategoryList({ items: [{ id: 201, name: "餐饮", type: "EXPENSE", bookable: false }] }), null);
    assert.equal(parseCategoryList({ items: [{ id: 1, name: "坏", type: "EXPENSE", bookable: true, children: [{ id: 0, name: "子", type: "EXPENSE", bookable: true }] }] }), null);
    const emptyChild = parseCategoryList({
      items: [{
        id: 201,
        name: "餐饮",
        type: "EXPENSE",
        bookable: false,
        children: [{ id: 202, name: "早餐", type: "EXPENSE", bookable: true, children: [] }],
      }],
    });
    assert.deepEqual(emptyChild?.[0]?.children[0], { id: 202, name: "早餐", type: "EXPENSE", bookable: true, children: [] });
    assert.equal(parseCategoryList({
      items: [{
        id: 1,
        name: "三级",
        type: "EXPENSE",
        bookable: false,
        children: [{ id: 2, name: "子", type: "EXPENSE", bookable: true, children: {} }],
      }],
    }), null);
    const third = parseCategoryList({
      items: [{
        id: 1,
        name: "三级",
        type: "EXPENSE",
        bookable: false,
        children: [{
          id: 2,
          name: "子",
          type: "EXPENSE",
          bookable: false,
          children: [{ id: 999, name: "末级", type: "EXPENSE", bookable: true, children: [] }],
        }],
      }],
    });
    assert.equal(third?.[0]?.children[0]?.children[0]?.name, "末级");
    assert.equal(parseCategoryList({
      items: [{
        id: 1,
        name: "四级",
        type: "EXPENSE",
        bookable: false,
        children: [{
          id: 2,
          name: "子",
          type: "EXPENSE",
          bookable: false,
          children: [{
            id: 3,
            name: "孙",
            type: "EXPENSE",
            bookable: false,
            children: [{ id: 4, name: "过深", type: "EXPENSE", bookable: true }],
          }],
        }],
      }],
    }), null);
    const official = parseCategoryList({
      items: [
        {
          id: 370,
          name: "交通",
          type: "EXPENSE",
          bookable: false,
          children: [{ id: 371, name: "公共交通", type: "EXPENSE", bookable: true, children: [] }],
        },
        { id: 374, name: "购物", type: "EXPENSE", bookable: true, children: [] },
      ],
    });
    assert.equal(official?.[0]?.children.length, 1);
    assert.deepEqual(official?.[0]?.children[0], { id: 371, name: "公共交通", type: "EXPENSE", bookable: true, children: [] });
    assert.deepEqual(official?.[1]?.children, []);
    assert.equal(parseCategoryList({ items: [{ id: 1, name: "坏", type: "EXPENSE", bookable: "yes", children: [] }] }), null);
    assert.equal(parseCategoryList({ items: [{ id: 1, name: "坏", type: "FOOD", bookable: true, children: [] }] }), null);
    assert.equal(parseCategoryList({ items: {} }), null);
  });

  it("parses tags and rejects malformed items", () => {
    assert.deepEqual(parseTag({ id: 301, name: "外卖", type: "EXPENSE" }), { id: 301, name: "外卖", type: "EXPENSE" });
    assert.deepEqual(parseTagList({ items: [] }), []);
    assert.equal(parseTag({ id: 0, name: "外卖", type: "EXPENSE" }), null);
    assert.equal(parseTag({ id: 1, name: "", type: "EXPENSE" }), null);
    assert.equal(parseTag({ id: 1, name: "外卖", type: "FOOD" }), null);
    assert.equal(parseTagList({ items: null }), null);
  });
});

describe("create transaction form", () => {
  it("accepts signed decimal strings and rejects zero or malformed amounts", () => {
    for (const amount of ["100", "100.0", "100.00", "-20", "+20.50"]) {
      const result = validateTransactionAmount(`  ${amount}  `);
      assert.equal(result.ok && result.value, amount);
    }
    for (const amount of ["0", "0.00", "-0.00", "1.234", "", "   ", "abc"]) {
      assert.equal(validateTransactionAmount(amount).ok, false);
    }
  });

  it("clears category and tags only when the type changes", () => {
    const form = {
      ...createTransactionForm(new Date("2026-09-30T16:00:00Z")),
      amount: "-20",
      name: "  午餐 ",
      categoryId: 203,
      selectedTagIds: [3, 1, 3],
      remark: "  ",
    };
    const changed = switchTransactionType(form, "INCOME");
    assert.equal(changed.type, "INCOME");
    assert.equal(changed.amount, "-20");
    assert.equal(changed.name, "  午餐 ");
    assert.equal(changed.businessTime, form.businessTime);
    assert.equal(changed.remark, "  ");
    assert.equal(changed.categoryId, null);
    assert.deepEqual(changed.selectedTagIds, []);
    assert.equal(form.categoryId, 203);
    const same = switchTransactionType(form, "EXPENSE");
    assert.equal(same.categoryId, 203);
    assert.deepEqual(same.selectedTagIds, [3, 1, 3]);
  });

  it("builds one immutable request and locates the saved month from the response", () => {
    const form = {
      ...createTransactionForm(new Date("2026-09-01T00:00:00Z")),
      type: "INCOME" as const,
      amount: "-20",
      name: "  工资 ",
      categoryId: null,
      selectedTagIds: [3, 1, 3],
      businessTime: "2026-08-31T23:59:59+08:00",
      remark: "   ",
    };
    const built = buildCreateTransactionSnapshot(form, 1001);
    assert.equal(built.ok, true);
    if (!built.ok) {
      return;
    }
    assert.equal(built.value.type, "INCOME");
    assert.equal(built.value.amount, "-20");
    assert.equal(built.value.name, "工资");
    assert.equal(built.value.categoryId, null);
    assert.equal(built.value.remark, null);
    assert.deepEqual(built.value.tagIds, [1, 3]);
    const body = createTransactionRequestBody(built.value);
    assert.deepEqual(body, {
      type: "INCOME",
      amount: "-20",
      name: "工资",
      tagIds: [1, 3],
      businessTime: "2026-08-31T23:59:59+08:00",
    });
    assert.equal("categoryId" in body, false);
    assert.equal("remark" in body, false);
    assert.deepEqual(createTransactionRequestBody(built.value), body);
    assert.equal(buildCreateTransactionSnapshot({ ...form, amount: "0" }, 1001).ok, false);
    const illegalType = { ...form, type: "TRANSFER" } as unknown as CreateTransactionForm;
    const rejected = buildCreateTransactionSnapshot(illegalType, 1001);
    assert.equal(rejected.ok, false);
    if (!rejected.ok) {
      assert.equal(rejected.reason, "type-invalid");
    }
    assert.equal(form.type, "INCOME");
    assert.equal(form.amount, "-20");
    const saved = parseTransactionResponse({
      ...transaction,
      id: 88,
      type: "INCOME",
      businessTime: "2026-08-31T23:59:59+08:00",
    });
    assert.deepEqual(saved === null ? null : postSaveResult(saved), {
      accountId: 1001,
      transactionId: 88,
      targetMonth: "2026-08",
    });
    assert.equal(businessMonth("2026-09-05T09:00:00+08:00"), "2026-09");
  });
});

describe("beijing business time", () => {
  it("formats Beijing seconds across month and year boundaries", () => {
    assert.equal(currentBeijingBusinessTime(new Date("2026-09-30T16:00:00.900Z")), "2026-10-01T00:00:00+08:00");
    assert.equal(currentBeijingBusinessTime(new Date("2026-12-31T16:00:00Z")), "2027-01-01T00:00:00+08:00");
    assert.equal(currentBeijingBusinessTime(new Date("2026-09-30T15:59:59Z")), "2026-09-30T23:59:59+08:00");
  });

  it("accepts only valid +08:00 second timestamps", () => {
    assert.equal(validateBusinessTime("2026-08-31T23:59:59+08:00"), true);
    assert.equal(validateBusinessTime("2024-02-29T00:00:00+08:00"), true);
    assert.equal(validateBusinessTime("2026-09-12T20:00:00Z"), false);
    assert.equal(validateBusinessTime("2026-09-12T20:00:00+09:00"), false);
    assert.equal(validateBusinessTime("2026-09-12T20:00:00.000+08:00"), false);
    assert.equal(validateBusinessTime("2026-02-31T00:00:00+08:00"), false);
  });

  it("allows a business time earlier than account creation", () => {
    const created = "2026-08-01T00:00:00+08:00";
    assert.equal(isBusinessTimeAllowed("2026-07-31T23:59:59+08:00", created), true);
    assert.equal(isBusinessTimeAllowed(created, created), true);
    assert.equal(isBusinessTimeAllowed("2026-10-01T00:00:00+08:00", created), true);
    assert.equal(isBusinessTimeAllowed("not-a-time", created), false);
  });

  it("builds canonical business time from normalized draft parts at save", () => {
    const created = "2026-08-01T00:00:00+08:00";
    assert.equal(normalizeBusinessDateInput("2026-02-30"), null);
    assert.equal(normalizeBusinessTimeInput("12:70:00"), null);
    const applied = applyBusinessTimeDraft({ date: "2026/10/7", time: "9:5" }, created);
    assert.equal(applied.ok, true);
    if (applied.ok) {
      assert.equal(applied.businessTime, "2026-10-07T09:05:00+08:00");
    }
    assert.equal(applyBusinessTimeDraft({ date: "2026-10-07", time: "25:00:00" }, created).ok, false);
  });
});
