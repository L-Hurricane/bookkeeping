import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { ApiError, createHttpClient, type HttpTransportRequest } from "../miniprogram/http/client";
import { acceptTransactionCreated } from "../miniprogram/ledger/post-save";
import { saveSession } from "../miniprogram/session/session";
import { TransactionResponseError, createTransactionApi, transactionResourcePath } from "../miniprogram/services/transaction";
import type { KeyValueStorage } from "../miniprogram/storage/key-value";
import type { Tag } from "../miniprogram/tag/tag";
import {
  abandonEditSession,
  applyCreateTagToEditSession,
  applyUpdateOutcome,
  beginUpdate,
  buildUpdateTransactionSnapshot,
  claimTagCreate,
  classifyDeleteError,
  classifyUpdateError,
  decideDelete,
  detailActionAccess,
  editCategoryText,
  editFormFromTransaction,
  editTagChoices,
  editingUpdate,
  enterEditSession,
  finishTagCreate,
  initialTagEditSession,
  resolveEditCancel,
  retryUpdate,
  tagCreateInFlight,
  transactionDetailView,
  updateTransactionRequestBody,
} from "../miniprogram/transaction/edit";
import type { CreateTransactionForm } from "../miniprogram/transaction/form";
import { editComposer, emptyBucket, openComposer } from "../miniprogram/transaction/reference";
import { parseDetailPageContext, transactionDetailPath } from "../miniprogram/transaction/navigation";
import type { TransactionView } from "../miniprogram/transaction/transaction";

const created = "2026-08-01T00:00:00+08:00";

const transaction: TransactionView = {
  id: 4001,
  accountId: 1001,
  type: "EXPENSE",
  amount: "30.00",
  name: "午餐",
  displayName: "午餐",
  category: { id: 203, name: "餐饮" },
  tags: [
    { id: 302, name: "公司" },
    { id: 301, name: "外卖" },
  ],
  businessTime: "2026-09-24T12:30:00+08:00",
  remark: "备注",
};

describe("transaction detail", () => {
  it("reads a matching transaction and rejects a bad contract", async () => {
    assert.equal(transactionResourcePath(1001, 4001), "/api/v1/accounts/1001/transactions/4001");
    const ok = apiFor(() => ({ statusCode: 200, data: responseBody() }));
    assert.equal((await ok.getTransaction(1001, 4001)).amount, "30.00");
    const broken = apiFor(() => ({ statusCode: 200, data: { id: 1 } }));
    await assert.rejects(() => broken.getTransaction(1001, 4001), TransactionResponseError);
    const otherId = apiFor(() => ({ statusCode: 200, data: { ...responseBody(), id: 4002 } }));
    await assert.rejects(() => otherId.getTransaction(1001, 4001), TransactionResponseError);
    const otherAccount = apiFor(() => ({ statusCode: 200, data: { ...responseBody(), accountId: 1002 } }));
    await assert.rejects(() => otherAccount.getTransaction(1001, 4001), TransactionResponseError);
    const missing = apiFor(() => ({ statusCode: 404, data: { error: { code: "RESOURCE_NOT_FOUND", message: "不存在" } } }));
    await assert.rejects(() => missing.getTransaction(1001, 4001), (error: unknown) => error instanceof ApiError && error.status === 404);
  });

  it("shows backend display fields and placeholders", () => {
    const view = transactionDetailView(transaction);
    assert.equal(view.title, "午餐");
    assert.equal(view.categoryLabel, "餐饮");
    assert.equal(view.businessTimeLabel, "2026-09-24 12:30:00");
    assert.equal(view.remarkLabel, "备注");
    const empty = transactionDetailView({
      ...transaction,
      displayName: null,
      category: null,
      tags: [],
      remark: null,
    });
    assert.equal(empty.title, "未命名交易");
    assert.equal(empty.categoryLabel, "未分类");
    assert.equal(empty.tagsLabel, "—");
    assert.equal(empty.remarkLabel, "—");
  });
});

describe("transaction edit", () => {
  it("fills the form from the transaction and keeps type out of the request", () => {
    const form = editFormFromTransaction(transaction);
    assert.equal(form.type, "EXPENSE");
    assert.equal(form.amount, "30.00");
    assert.equal(form.name, "午餐");
    assert.equal(form.categoryId, 203);
    assert.deepEqual(form.selectedTagIds, [302, 301]);
    assert.equal(form.businessTime, transaction.businessTime);
    assert.equal(form.remark, "备注");
    const changed: CreateTransactionForm = { ...form, type: "INCOME" };
    assert.equal(buildUpdateTransactionSnapshot(changed, 1001, 1001, 4001, "EXPENSE", created).ok, false);
    const built = buildUpdateTransactionSnapshot(
      { ...form, name: "  ", remark: "", selectedTagIds: [302, 301, 301], categoryId: null },
      1001,
      1001,
      4001,
      "EXPENSE",
      created,
    );
    assert.equal(built.ok, true);
    if (!built.ok) {
      return;
    }
    const body = updateTransactionRequestBody(built.value);
    assert.equal("type" in body, false);
    assert.equal(body.accountId, 1001);
    assert.equal(body.name, undefined);
    assert.equal(body.categoryId, undefined);
    assert.equal(body.remark, undefined);
    assert.deepEqual(body.tagIds, [301, 302]);
    assert.equal(
      buildUpdateTransactionSnapshot({ ...form, businessTime: "2026-07-01T00:00:00+08:00" }, 1001, 1001, 4001, "EXPENSE", created).ok,
      true,
    );
    const moved = buildUpdateTransactionSnapshot(form, 1001, 1002, 4001, "EXPENSE", created);
    assert.equal(moved.ok, true);
    if (moved.ok) {
      assert.equal(moved.value.sourceAccountId, 1001);
      assert.equal(moved.value.accountId, 1002);
      assert.equal(updateTransactionRequestBody(moved.value).accountId, 1002);
    }
    assert.equal(resolveEditCancel("continue"), "stay");
    assert.equal(resolveEditCancel("abandon"), "detail");
  });

  it("retries the original snapshot after an uncertain update", async () => {
    const form = editFormFromTransaction(transaction);
    const built = buildUpdateTransactionSnapshot(form, 1001, 1001, 4001, "EXPENSE", created);
    assert.equal(built.ok, true);
    if (!built.ok) {
      return;
    }
    const submitting = beginUpdate(editingUpdate(), built.value);
    assert.equal(submitting?.phase, "submitting");
    const uncertain = applyUpdateOutcome(submitting ?? editingUpdate(), classifyUpdateError(new ApiError(0, "NETWORK_ERROR", "网络异常")));
    assert.equal(uncertain.phase, "uncertain");
    assert.equal(applyUpdateOutcome(submitting ?? editingUpdate(), classifyUpdateError(new ApiError(500, "INTERNAL", "失败"))).phase, "uncertain");
    assert.equal(applyUpdateOutcome(submitting ?? editingUpdate(), classifyUpdateError(new TransactionResponseError())).phase, "uncertain");
    const retried = retryUpdate(uncertain);
    assert.equal(retried?.attempt?.amount, "30.00");
    assert.equal(retried?.attempt?.businessTime, "2026-09-24T12:30:00+08:00");
    assert.equal(retried?.attempt?.transactionId, 4001);
    assert.notEqual(retried?.attempt?.amount, "100");
    const saved = applyUpdateOutcome(retried ?? editingUpdate(), {
      kind: "success",
      transaction: { ...transaction, businessTime: "2026-08-20T08:00:00+08:00" },
    });
    assert.equal(saved.phase, "success");
    assert.equal(saved.postSave?.targetMonth, "2026-08");
    assert.equal(saved.postSave?.transactionId, 4001);
    const home = acceptTransactionCreated({ selectedMonth: "2026-09", pending: null, highlightKey: "" }, saved.postSave);
    assert.equal(home.selectedMonth, "2026-08");
    assert.equal(home.pending?.kind, "transaction");
    assert.equal(home.pending?.recordId, 4001);
    const otherType = apiFor(() => ({ statusCode: 200, data: { ...responseBody(), type: "INCOME" } }));
    await assert.rejects(() => otherType.updateTransaction(built.value), TransactionResponseError);
    const mismatch = apiFor(() => ({ statusCode: 200, data: { ...responseBody(), id: 9 } }));
    await assert.rejects(() => mismatch.updateTransaction(built.value), TransactionResponseError);
  });
});

describe("edit tag session", () => {
  it("drops an abandoned composer before the next edit", () => {
    let session = enterEditSession(initialTagEditSession());
    session = { ...session, composer: editComposer(openComposer(session.composer), "旧标签") };
    session = { ...session, composer: { ...session.composer, error: "标签名称不能为空" } };
    session = enterEditSession(abandonEditSession(session));
    assert.equal(session.composer.open, false);
    assert.equal(session.composer.draft, "");
    assert.equal(session.composer.error, "");
    assert.notEqual(session.composer.generation, 0);
  });

  it("keeps a tag created by an old edit without selecting it in the new edit", () => {
    let session = enterEditSession(initialTagEditSession());
    session = { ...session, composer: openComposer(session.composer) };
    const claimed = claimTagCreate(session);
    session = enterEditSession(abandonEditSession(claimed.session));
    const selected = [301, 302];
    const applied = applyCreateTagToEditSession(session, claimed.request, emptyBucket<Tag>(), selected, {
      status: "success",
      tag: { id: 8, name: "旧标签", type: "EXPENSE" },
    });
    assert.deepEqual(applied.bucket.items.map((tag) => tag.id), [8]);
    assert.deepEqual(applied.selectedTagIds, selected);
    assert.equal(applied.session.composer.open, false);
    assert.equal(applied.session.composer.error, "");
  });

  it("does not copy an old create failure into the new composer", () => {
    let session = enterEditSession(initialTagEditSession());
    session = { ...session, composer: openComposer(session.composer) };
    const claimed = claimTagCreate(session);
    session = enterEditSession(abandonEditSession(claimed.session));
    session = { ...session, composer: { ...openComposer(session.composer), error: "" } };
    const applied = applyCreateTagToEditSession(session, claimed.request, emptyBucket<Tag>(), [], {
      status: "failure",
      message: "标签创建失败，请稍后重试",
    });
    assert.equal(applied.session.composer.error, "");
    assert.equal(applied.session.composer.open, true);
  });

  it("does not let an old request finish clear a newer in-flight create", () => {
    let session = enterEditSession(initialTagEditSession());
    session = { ...session, composer: openComposer(session.composer) };
    const first = claimTagCreate(session);
    session = enterEditSession(abandonEditSession(first.session));
    session = { ...session, composer: openComposer(session.composer) };
    const second = claimTagCreate(session);
    session = finishTagCreate(second.session, first.request);
    assert.equal(session.request?.sessionId, second.request.sessionId);
    assert.equal(session.request?.generation, second.request.generation);
    assert.equal(tagCreateInFlight(session), true);
  });
});

describe("edit reference failure", () => {
  it("keeps the saved category and tags visible when their lists are unavailable", () => {
    const form = editFormFromTransaction(transaction);
    assert.equal(editCategoryText([], form.categoryId, transaction.category), "餐饮");
    const chips = editTagChoices(transaction.tags, [], form.type);
    assert.deepEqual(
      chips.filter((tag) => form.selectedTagIds.includes(tag.id)).map((tag) => tag.name),
      ["公司", "外卖"],
    );
    const built = buildUpdateTransactionSnapshot(form, 1001, 1001, 4001, "EXPENSE", created);
    assert.equal(built.ok, true);
    if (!built.ok) {
      return;
    }
    assert.equal(built.value.categoryId, 203);
    assert.deepEqual(built.value.tagIds, [301, 302]);
    const ready = editCategoryText(
      [{ id: 20, name: "餐饮", type: "EXPENSE", bookable: false, children: [{ id: 203, name: "午餐", type: "EXPENSE", bookable: true }] }],
      203,
      transaction.category,
    );
    assert.equal(ready, "餐饮 / 午餐");
  });

  it("shows an explicit clear as uncategorized and omits categoryId", () => {
    const form = { ...editFormFromTransaction(transaction), categoryId: null };
    assert.equal(editCategoryText([], form.categoryId, transaction.category), "未分类");
    const built = buildUpdateTransactionSnapshot(form, 1001, 1001, 4001, "EXPENSE", created);
    assert.equal(built.ok, true);
    if (!built.ok) {
      return;
    }
    assert.equal(built.value.categoryId, null);
    assert.equal(updateTransactionRequestBody(built.value).categoryId, undefined);
  });
});

describe("delete interaction lock", () => {
  it("allows edit and delete only while idle", () => {
    assert.deepEqual(detailActionAccess("idle"), { edit: true, delete: true, retry: false });
  });

  it("blocks edit and another delete while a delete is in flight", () => {
    assert.deepEqual(detailActionAccess("deleting"), { edit: false, delete: false, retry: false });
  });

  it("keeps edit closed after an uncertain delete and leaves retry", () => {
    assert.deepEqual(detailActionAccess("uncertain"), { edit: false, delete: false, retry: true });
  });
});

describe("transaction delete", () => {
  it("sends one delete only after confirmation and treats absence as finished", async () => {
    assert.equal(decideDelete(false), "cancel");
    assert.equal(decideDelete(true), "send");
    let calls = 0;
    const api = apiFor(() => {
      calls += 1;
      return { statusCode: calls === 1 ? 500 : 204, data: calls === 1 ? { error: { code: "INTERNAL", message: "失败" } } : "" };
    });
    if (decideDelete(false) === "send") {
      await api.deleteTransaction(1001, 4001);
    }
    assert.equal(calls, 0);
    await assert.rejects(() => api.deleteTransaction(1001, 4001), (error: unknown) => {
      assert.equal(classifyDeleteError(error).kind, "uncertain");
      return true;
    });
    await api.deleteTransaction(1001, 4001);
    assert.equal(calls, 2);
    assert.equal(classifyDeleteError(new ApiError(404, "RESOURCE_NOT_FOUND", "不存在")).kind, "absent");
    assert.equal(classifyDeleteError(new ApiError(401, "SESSION_INVALID", "会话无效")).kind, "unauthorized");
    assert.equal(classifyDeleteError(new ApiError(403, "FORBIDDEN", "拒绝")).kind, "definite");
    const path = transactionDetailPath(1001, 4001, created, "2026-09");
    const parsed = parseDetailPageContext({
      accountId: "1001",
      transactionId: "4001",
      accountCreatedTime: encodeURIComponent(created),
      originMonth: "2026-09",
    });
    assert.equal(path.includes("transactionId=4001"), true);
    assert.equal(parsed?.originMonth, "2026-09");
    assert.equal(parseDetailPageContext({ accountId: "1001", transactionId: "0", accountCreatedTime: created, originMonth: "2026-09" }), null);
  });
});

function responseBody(): Record<string, unknown> {
  return {
    id: 4001,
    accountId: 1001,
    type: "EXPENSE",
    amount: "30.00",
    name: "午餐",
    displayName: "午餐",
    category: { id: 203, name: "餐饮" },
    tags: [
      { id: 302, name: "公司" },
      { id: 301, name: "外卖" },
    ],
    businessTime: "2026-09-24T12:30:00+08:00",
    remark: "备注",
  };
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
  return createTransactionApi(
    createHttpClient({
      baseUrl: "http://127.0.0.1:8080",
      storage,
      now: () => new Date("2026-09-25T08:00:00Z"),
      onUnauthorized: () => undefined,
      transport: async (request) => responder(request),
    }),
  );
}
