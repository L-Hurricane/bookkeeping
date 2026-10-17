import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { CategoryNode } from "../miniprogram/category/category";
import { createHttpClient, type HttpTransportRequest } from "../miniprogram/http/client";
import { saveSession } from "../miniprogram/session/session";
import { CategoryResponseError, createCategoryApi } from "../miniprogram/services/category";
import { TagResponseError, createTagApi } from "../miniprogram/services/tag";
import type { KeyValueStorage } from "../miniprogram/storage/key-value";
import type { Tag } from "../miniprogram/tag/tag";
import {
  applyBusinessTimeDraft,
  applyDatePickerValue,
  applyNow,
  applyTimePickerValue,
  applyToday,
  applyYesterday,
  businessTimeDisplay,
  businessTimeDraft,
  businessTimeFromParts,
  businessTimePickerMinute,
  isFutureBusinessTime,
  normalizeBusinessDateInput,
  normalizeBusinessTimeInput,
  normalizeDateOnBlur,
  normalizeTimeOnBlur,
  previousBeijingDate,
  resolveBusinessTimeEdit,
} from "../miniprogram/transaction/business-time";
import { createTransactionForm, switchTransactionType, validateTransactionAmount } from "../miniprogram/transaction/form";
import { canOpenCreateTransaction, parseCreatePageContext, transactionCreatePath } from "../miniprogram/transaction/navigation";
import {
  acceptCreatedTag,
  amountFeedback,
  applyCreatedTag,
  beginBucket,
  categoryLabel,
  categoryRows,
  createTagFailureMessage,
  editComposer,
  emptyBucket,
  emptyComposer,
  finishBucket,
  mergeTagLists,
  openComposer,
  prepareCreateTag,
  rememberCreatedTag,
  replaceItems,
  retireComposer,
  resolveCancel,
  settleCreateTagComposer,
  shouldLoadReference,
  showsReference,
  switchCreateType,
  toggleTagSelection,
  visibleCreateTagError,
} from "../miniprogram/transaction/reference";

const created = "2026-08-01T00:00:00+08:00";
const base = "2026-09-26T11:28:00+08:00";

const categories: CategoryNode[] = [
  {
    id: 1,
    name: "餐饮",
    type: "EXPENSE",
    bookable: false,
    children: [
      { id: 11, name: "午餐", type: "EXPENSE", bookable: true },
      { id: 13, name: "隐藏", type: "EXPENSE", bookable: false },
    ],
  },
  { id: 2, name: "购物", type: "EXPENSE", bookable: true, children: [] },
  {
    id: 3,
    name: "居住",
    type: "EXPENSE",
    bookable: true,
    children: [{ id: 31, name: "房租", type: "EXPENSE", bookable: true }],
  },
];

const expenseTag: Tag = { id: 9, name: "外卖", type: "EXPENSE" };
const incomeTag: Tag = { id: 8, name: "工资", type: "INCOME" };

describe("create page navigation", () => {
  it("encodes the Beijing offset and opens only after the account is known", () => {
    const path = transactionCreatePath(1001, created);
    assert.equal(path, "/pages/transaction-create/index?accountId=1001&accountCreatedTime=2026-08-01T00%3A00%3A00%2B08%3A00");
    assert.equal(path.includes("+"), false);
    assert.equal(canOpenCreateTransaction("ledger-loading", false), true);
    assert.equal(canOpenCreateTransaction("ready", false), true);
    assert.equal(canOpenCreateTransaction("ledger-error", false), true);
    assert.equal(canOpenCreateTransaction("ready", true), false);
    assert.equal(canOpenCreateTransaction("account-loading", false), false);
    assert.equal(canOpenCreateTransaction("account-error", false), false);
  });

  it("rejects an invalid account id or created time", () => {
    assert.deepEqual(parseCreatePageContext({ accountId: "1001", accountCreatedTime: created }), {
      accountId: 1001,
      accountCreatedTime: created,
    });
    assert.equal(parseCreatePageContext({ accountId: "0", accountCreatedTime: created }), null);
    assert.equal(parseCreatePageContext({ accountId: "9007199254740993", accountCreatedTime: created }), null);
    assert.equal(parseCreatePageContext({ accountId: "1001", accountCreatedTime: "2026-08-01T00:00:00Z" }), null);
    assert.equal(parseCreatePageContext({ accountId: "1001" }), null);
  });

  it("accepts a decoded or once-encoded business time and rejects a bad query", () => {
    const canonical = "2026-09-25T10:00:00+08:00";
    assert.deepEqual(parseCreatePageContext({ accountId: "1001", accountCreatedTime: canonical }), {
      accountId: 1001,
      accountCreatedTime: canonical,
    });
    assert.deepEqual(parseCreatePageContext({ accountId: "1001", accountCreatedTime: "2026-09-25T10%3A00%3A00%2B08%3A00" }), {
      accountId: 1001,
      accountCreatedTime: canonical,
    });
    assert.equal(parseCreatePageContext({ accountId: "1001", accountCreatedTime: "2026-09-25T10%ZZ" }), null);
    assert.equal(parseCreatePageContext({ accountId: "1001", accountCreatedTime: "2026-09-25T10:00:00+09:00" }), null);
  });

  it("round-trips an encoded create path back to the canonical context", () => {
    const canonical = "2026-09-25T10:00:00+08:00";
    const path = transactionCreatePath(1001, canonical);
    const encoded = path.split("accountCreatedTime=")[1];
    assert.equal(encoded?.includes("+"), false);
    assert.equal(encoded?.includes("%2B"), true);
    assert.deepEqual(parseCreatePageContext({ accountId: "1001", accountCreatedTime: encoded }), {
      accountId: 1001,
      accountCreatedTime: canonical,
    });
  });
});

describe("category reference data", () => {
  it("loads an authenticated official list and rejects a malformed or cross-type response", async () => {
    const requests: HttpTransportRequest[] = [];
    const api = categoryApiFor((request) => {
      requests.push(request);
      return {
        statusCode: 200,
        data: {
          items: [
            {
              id: 1,
              name: "餐饮",
              type: "EXPENSE",
              bookable: false,
              children: [
                { id: 11, name: "午餐", type: "EXPENSE", bookable: true, children: [] },
                { id: 13, name: "隐藏", type: "EXPENSE", bookable: false },
              ],
            },
            categories[1],
            categories[2],
          ],
        },
      };
    });
    const loaded = await api.listCategories("EXPENSE");
    assert.equal(requests[0]?.url.endsWith("/api/v1/categories?type=EXPENSE"), true);
    assert.equal(requests[0]?.header.Authorization, "Bearer opaque-token");
    assert.deepEqual(loaded[0]?.children[0], { id: 11, name: "午餐", type: "EXPENSE", bookable: true });
    assert.equal(loaded[0]?.children[1]?.name, "隐藏");

    const malformed = categoryApiFor(() => ({ statusCode: 200, data: { items: {} } }));
    await assert.rejects(() => malformed.listCategories("EXPENSE"), CategoryResponseError);

    const crossed = categoryApiFor(() => ({
      statusCode: 200,
      data: {
        items: [
          {
            id: 1,
            name: "餐饮",
            type: "EXPENSE",
            bookable: false,
            children: [{ id: 11, name: "午餐", type: "INCOME", bookable: true }],
          },
        ],
      },
    }));
    await assert.rejects(() => crossed.listCategories("EXPENSE"), CategoryResponseError);
  });

  it("keeps a per-type cache and ignores a stale result for the other type", () => {
    let expense = emptyBucket<CategoryNode>();
    assert.equal(shouldLoadReference(expense.phase), true);
    const first = beginBucket(expense);
    expense = finishBucket(first.bucket, first.generation, true, categories, replaceItems) ?? expense;
    assert.equal(expense.phase, "ready");
    assert.equal(shouldLoadReference(expense.phase), false);

    let failed = emptyBucket<CategoryNode>();
    const failedStart = beginBucket(failed);
    failed = finishBucket(failedStart.bucket, failedStart.generation, true, null, replaceItems) ?? failed;
    assert.equal(failed.phase, "error");
    assert.equal(shouldLoadReference(failed.phase), true);
    const retry = beginBucket(failed);
    failed = finishBucket(retry.bucket, retry.generation, true, categories, replaceItems) ?? failed;
    assert.equal(failed.phase, "ready");

    const staleStart = beginBucket(emptyBucket<CategoryNode>());
    const stale = finishBucket(staleStart.bucket, staleStart.generation, true, categories, replaceItems);
    assert.equal(stale?.phase, "ready");
    assert.equal(showsReference("INCOME", "EXPENSE"), false);
    const staleError = finishBucket(staleStart.bucket, staleStart.generation, true, null, replaceItems);
    assert.equal(staleError?.phase, "error");
    assert.equal(showsReference("INCOME", "EXPENSE"), false);
  });

  it("selects an uncategorized value, a bookable leaf, and a child, while a parent stays a group", () => {
    const rows = categoryRows(categories);
    assert.equal(categoryLabel(categories, null), "未分类");
    assert.equal(rows.some((row) => row.kind === "option" && row.id === 2 && row.label === "购物"), true);
    assert.equal(rows.some((row) => row.id === 1), false);
    assert.equal(rows.some((row) => row.id === 3), false);
    assert.equal(rows.find((row) => row.id === 1)?.kind, undefined);
    assert.equal(rows.find((row) => row.key === "group-1")?.kind, "group");
    assert.equal(rows.find((row) => row.key === "group-3")?.kind, "group");
    assert.equal(rows.some((row) => row.id === 13), false);
    assert.equal(rows.some((row) => row.kind === "option" && row.id === 11), true);
    assert.equal(categoryLabel(categories, 11), "餐饮 / 午餐");
    assert.equal(categoryLabel(categories, 2), "购物");
  });
});

describe("tag reference data", () => {
  it("lists and creates authenticated tags and rejects a bad contract", async () => {
    const requests: HttpTransportRequest[] = [];
    const api = tagApiFor((request) => {
      requests.push(request);
      if (request.method === "GET") {
        return { statusCode: 200, data: { items: [expenseTag] } };
      }
      return { statusCode: 201, data: { id: 15, name: "报销", type: "EXPENSE" } };
    });
    assert.deepEqual(await api.listTags("EXPENSE"), [expenseTag]);
    assert.equal(requests[0]?.url.endsWith("/api/v1/tags?type=EXPENSE"), true);
    assert.equal(requests[0]?.header.Authorization, "Bearer opaque-token");
    const createdTag = await api.createTag("EXPENSE", "报销");
    assert.equal(requests[1]?.url.endsWith("/api/v1/tags"), true);
    assert.equal(requests[1]?.data, JSON.stringify({ type: "EXPENSE", name: "报销" }));
    assert.equal(createdTag.id, 15);

    const malformed = tagApiFor((request) => ({ statusCode: 200, data: request.method === "GET" ? { items: null } : { id: 1 } }));
    await assert.rejects(() => malformed.listTags("EXPENSE"), TagResponseError);
    await assert.rejects(() => malformed.createTag("EXPENSE", "报销"), TagResponseError);

    const crossed = tagApiFor(() => ({ statusCode: 201, data: { ...incomeTag } }));
    await assert.rejects(() => crossed.createTag("EXPENSE", "工资"), TagResponseError);
    const listed = tagApiFor(() => ({ statusCode: 200, data: { items: [incomeTag] } }));
    await assert.rejects(() => listed.listTags("EXPENSE"), TagResponseError);
  });

  it("toggles tag ids without duplicates and clears them on a type switch", () => {
    const selected = toggleTagSelection([], 9, [expenseTag]);
    assert.deepEqual(selected, [9]);
    assert.deepEqual(toggleTagSelection(selected, 9, [expenseTag]), []);
    assert.deepEqual(toggleTagSelection(selected, 9, [expenseTag]), []);
    const kept = toggleTagSelection(selected, 8, [expenseTag]);
    assert.deepEqual(kept, [9]);
    const form = switchCreateType(
      { ...createTransactionForm(new Date("2026-09-26T03:28:00Z")), amount: "12", name: "午饭", selectedTagIds: [9], categoryId: 11, remark: "a", businessTime: base },
      "INCOME",
    );
    assert.equal(form.clearTagDraft, true);
    assert.deepEqual(form.form.selectedTagIds, []);
    assert.equal(form.form.categoryId, null);
    assert.equal(form.form.amount, "12");
    assert.equal(form.form.businessTime, base);
    const same = switchCreateType(form.form, "INCOME");
    assert.equal(same.clearTagDraft, false);
    assert.equal(switchTransactionType(form.form, "INCOME").type, "INCOME");
  });

  it("creates a tag independently and keeps it when an older list returns", () => {
    assert.equal(prepareCreateTag("  ", [expenseTag]).status, "empty");
    assert.equal(prepareCreateTag(" 外卖 ", [expenseTag]).status, "duplicate");
    assert.deepEqual(prepareCreateTag(" 报销 ", [expenseTag]), { status: "create", name: "报销" });

    const createdTag: Tag = { id: 15, name: "报销", type: "EXPENSE" };
    const current = applyCreatedTag([expenseTag], [], "EXPENSE", createdTag);
    assert.deepEqual(current.selected, [15]);
    assert.equal(current.tags.some((tag) => tag.id === 15), true);

    const switched = applyCreatedTag([expenseTag], [8], "INCOME", createdTag);
    assert.deepEqual(switched.selected, [8]);
    assert.equal(switched.tags.some((tag) => tag.id === 15), true);
    assert.equal(visibleCreateTagError("INCOME", "EXPENSE", "标签名称已存在"), null);
    assert.equal(createTagFailureMessage(409, "TAG_NAME_CONFLICT"), "标签名称已存在");
    assert.equal(createTagFailureMessage(500, "INTERNAL"), "标签创建失败，请稍后重试");

    const merged = mergeTagLists(current.tags, [expenseTag]);
    assert.deepEqual(merged.map((tag) => tag.id), [9, 15]);
  });

  it("keeps a failed tag list failed after creating a tag, then merges it on retry", () => {
    const createdTag: Tag = { id: 15, name: "报销", type: "EXPENSE" };
    const failed = rememberCreatedTag({ generation: 1, phase: "error", items: [] }, createdTag);
    assert.equal(failed.phase, "error");
    assert.deepEqual(failed.items.map((tag) => tag.id), [15]);
    assert.deepEqual(applyCreatedTag([], [], "EXPENSE", createdTag).selected, [15]);
    assert.equal(rememberCreatedTag({ generation: 2, phase: "ready", items: [expenseTag] }, createdTag).phase, "ready");
    assert.equal(rememberCreatedTag(emptyBucket<Tag>(), createdTag).phase, "idle");

    const retry = beginBucket(failed);
    const ready = finishBucket(retry.bucket, retry.generation, true, [expenseTag], mergeTagLists);
    assert.equal(ready?.phase, "ready");
    assert.deepEqual(ready?.items.map((tag) => tag.id), [9, 15]);
  });

  it("does not let an older create request overwrite a newer composer", () => {
    let expense = editComposer(openComposer(emptyComposer()), "A");
    const requestGeneration = expense.generation;
    expense = retireComposer(expense);
    const income = editComposer(openComposer(emptyComposer()), "收入标签");
    expense = editComposer(openComposer(expense), "B");

    const succeeded = settleCreateTagComposer(expense, requestGeneration, { status: "success" });
    assert.equal(succeeded.open, true);
    assert.equal(succeeded.draft, "B");
    assert.equal(succeeded.error, "");
    const failed = settleCreateTagComposer(expense, requestGeneration, { status: "failure", message: "标签创建失败，请稍后重试" });
    assert.equal(failed.open, true);
    assert.equal(failed.draft, "B");
    assert.equal(failed.error, "");
    assert.equal(income.open, true);
    assert.equal(income.draft, "收入标签");
    assert.equal(income.error, "");

    const owned = settleCreateTagComposer(editComposer(openComposer(emptyComposer()), "A"), 1, { status: "failure", message: "标签名称已存在" });
    assert.equal(owned.draft, "A");
    assert.equal(owned.open, true);
    assert.equal(owned.error, "标签名称已存在");
  });
});

describe("business time editor", () => {
  const canonical = "2026-09-26T12:46:06+08:00";

  it("displays and edits a canonical Beijing time as date and clock text", () => {
    assert.equal(businessTimeDisplay(canonical), "2026-09-26 12:46:06");
    assert.deepEqual(businessTimeDraft(canonical), { date: "2026-09-26", time: "12:46:06" });
    assert.equal(businessTimeFromParts("2026-09-26", "12:46:06"), canonical);
    assert.equal(businessTimeFromParts("2024-02-29", "00:00:00"), "2024-02-29T00:00:00+08:00");
    for (const date of ["2023-02-29", "2026-13-01", ""]) {
      assert.equal(businessTimeFromParts(date, "12:46:06"), null);
    }
    for (const time of ["24:00:00", "12:60:00", "12:00:60", ""]) {
      assert.equal(businessTimeFromParts("2026-09-26", time), null);
    }
  });

  it("normalizes flexible date and time input on submit without rewriting illegal values", () => {
    assert.equal(normalizeBusinessDateInput("2026-10-7"), "2026-10-07");
    assert.equal(normalizeBusinessDateInput("2026/10/7"), "2026-10-07");
    assert.equal(normalizeBusinessDateInput("2026-9-26"), "2026-09-26");
    assert.equal(normalizeBusinessDateInput("2026-02-30"), null);
    assert.equal(normalizeDateOnBlur("2026-02-30"), "2026-02-30");

    const cases: Array<[string, string]> = [
      ["12:1:0", "12:01:00"],
      ["9:5", "09:05:00"],
      ["12:30", "12:30:00"],
      ["8:3:7", "08:03:07"],
      ["120000", "12:00:00"],
      ["093015", "09:30:15"],
    ];
    for (const [input, expected] of cases) {
      assert.equal(normalizeBusinessTimeInput(input), expected);
    }
    assert.equal(normalizeBusinessTimeInput("25:00:00"), null);
    assert.equal(normalizeBusinessTimeInput("12:70:00"), null);
    assert.equal(normalizeTimeOnBlur("25:00:00"), "25:00:00");
  });

  it("keeps seconds when only the date changes and zeroes seconds from the native time picker", () => {
    const draft = { date: "2026-09-26", time: "12:46:06" };
    assert.deepEqual(applyDatePickerValue("2026-10-01", draft), { date: "2026-10-01", time: "12:46:06" });
    assert.deepEqual(applyTimePickerValue("13:15", draft), { date: "2026-09-26", time: "13:15:00" });
    assert.equal(businessTimePickerMinute("12:46:06"), "12:46");
    assert.equal(businessTimePickerMinute("12:1:0"), "12:01");
  });

  it("uses Beijing calendar shortcuts without rewriting an invalid clock", () => {
    const crossed = new Date("2026-09-30T16:00:05Z");
    assert.deepEqual(applyNow(crossed), { date: "2026-10-01", time: "00:00:05" });
    const kept = applyToday({ date: "2020-01-01", time: "09:30" }, new Date("2026-09-26T03:28:00Z"));
    assert.deepEqual(kept, { date: "2026-09-26", time: "09:30" });
    assert.equal(previousBeijingDate(new Date("2026-09-30T16:00:00Z")), "2026-09-30");
    assert.equal(previousBeijingDate(new Date("2025-12-31T16:00:00Z")), "2025-12-31");
    assert.equal(previousBeijingDate(new Date("2024-02-29T16:00:00Z")), "2024-02-29");
    assert.equal(applyYesterday({ date: "2026-10-02", time: "01:02:03" }, new Date("2026-09-30T16:00:00Z")).time, "01:02:03");
  });

  it("confirms a legal draft once and leaves the form unchanged when cancelled", () => {
    const accountCreated = "2026-08-01T00:00:00+08:00";
    const early = applyBusinessTimeDraft({ date: "2026-07-31", time: "23:59:59" }, accountCreated);
    assert.equal(early.ok, false);
    if (!early.ok) {
      assert.equal(early.message, "交易时间不得早于账户创建时间");
    }
    assert.equal(applyBusinessTimeDraft({ date: "2026-08-01", time: "00:00:00" }, accountCreated).ok, true);
    assert.equal(applyBusinessTimeDraft({ date: "2026-09-26", time: "12:46:06" }, accountCreated).ok, true);
    const future = businessTimeFromParts("2026-10-01", "00:00:01");
    assert.equal(isFutureBusinessTime(future ?? "", new Date("2026-09-26T03:28:00Z")), true);

    const edited = { date: "2020-01-01", time: "00:00:00" };
    const cancelled = resolveBusinessTimeEdit("cancel", canonical, edited, accountCreated);
    assert.equal(cancelled.businessTime, canonical);
    assert.equal(cancelled.open, false);

    const confirmed = resolveBusinessTimeEdit("confirm", canonical, { date: "2026-09-26", time: "13:15:28" }, accountCreated);
    assert.equal(confirmed.businessTime, "2026-09-26T13:15:28+08:00");
    assert.equal(confirmed.open, false);
    assert.equal(confirmed.error, "");

    const normalized = resolveBusinessTimeEdit("confirm", canonical, { date: "2026-09-26", time: "09:30" }, accountCreated);
    assert.equal(normalized.businessTime, "2026-09-26T09:30:00+08:00");
    assert.equal(normalized.open, false);

    const rejected = resolveBusinessTimeEdit("confirm", canonical, { date: "2026-09-26", time: "25:00:00" }, accountCreated);
    assert.equal(rejected.businessTime, canonical);
    assert.equal(rejected.open, true);
    assert.equal(rejected.error, "请输入有效的日期和时间");
  });

  it("describes amount problems without accepting zero", () => {
    assert.equal(amountFeedback("", validateTransactionAmount("")), "请输入金额");
    assert.equal(amountFeedback("0", validateTransactionAmount("0")), "金额不能为 0");
    assert.equal(amountFeedback("1.234", validateTransactionAmount("1.234")), "请输入最多两位小数的金额");
    assert.equal(amountFeedback("-12.5", validateTransactionAmount("-12.5")), null);
  });
});

describe("create page lifetime and cancel", () => {
  it("ignores reference data and a created tag after the page unloads", () => {
    const started = beginBucket(emptyBucket<Tag>());
    assert.equal(finishBucket(started.bucket, started.generation, false, [expenseTag], mergeTagLists), null);
    assert.equal(acceptCreatedTag(false, [], [], "EXPENSE", expenseTag), null);
  });

  it("keeps the form when editing continues and leaves created tags in place when abandoning", () => {
    const form = createTransactionForm(new Date("2026-09-26T03:28:00Z"));
    const kept = resolveCancel("continue", form, [15]);
    assert.equal(kept.action, "stay");
    assert.equal(kept.form, form);
    assert.deepEqual(kept.createdTagIds, [15]);
    const left = resolveCancel("abandon", form, [15]);
    assert.equal(left.action, "leave");
    assert.equal(left.form, null);
    assert.deepEqual(left.createdTagIds, [15]);
  });
});

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

function httpFor(responder: (request: HttpTransportRequest) => { statusCode: number; data: unknown }) {
  const storage = memoryStorage();
  saveSession(storage, { token: "opaque-token", expiresAt: "2099-01-01T00:00:00Z" });
  return createHttpClient({
    baseUrl: "http://127.0.0.1:8080",
    storage,
    now: () => new Date("2026-09-26T03:28:00Z"),
    onUnauthorized: () => undefined,
    transport: async (request) => responder(request),
  });
}

function categoryApiFor(responder: (request: HttpTransportRequest) => { statusCode: number; data: unknown }) {
  return createCategoryApi(httpFor(responder));
}

function tagApiFor(responder: (request: HttpTransportRequest) => { statusCode: number; data: unknown }) {
  return createTagApi(httpFor(responder));
}
