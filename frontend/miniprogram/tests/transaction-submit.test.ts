import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { ApiError, createHttpClient, type HttpTransportRequest } from "../miniprogram/http/client";
import { saveSession } from "../miniprogram/session/session";
import { TransactionResponseError, createTransactionApi, transactionCreatePath } from "../miniprogram/services/transaction";
import type { KeyValueStorage } from "../miniprogram/storage/key-value";
import { createTransactionRequestBody, type CreateTransactionForm, type CreateTransactionSnapshot } from "../miniprogram/transaction/form";
import { IdempotencyKeyError, createIdempotencyKey, randomBytesToIdempotencyKey } from "../miniprogram/transaction/idempotency";
import {
  CONFLICT_MESSAGE,
  PREPARE_MESSAGE,
  UNCERTAIN_MESSAGE,
  WAIT_TAG_MESSAGE,
  applySubmitOutcome,
  beginAttempt,
  canSave,
  classifySubmitError,
  editingSubmission,
  formLocked,
  interactionLocked,
  openSubmission,
  prepareSave,
  retryAttempt,
} from "../miniprogram/transaction/submission";
import type { TransactionView } from "../miniprogram/transaction/transaction";

const created = "2026-08-01T00:00:00+08:00";
const key = "a14f0c11b2d3e4f5061728394a5b6c7d";

const snapshot: CreateTransactionSnapshot = {
  accountId: 1001,
  type: "EXPENSE",
  amount: "30",
  name: "午餐",
  categoryId: 203,
  tagIds: [301],
  businessTime: "2026-09-24T12:30:00+08:00",
  remark: null,
};

const form: CreateTransactionForm = {
  type: "EXPENSE",
  amount: "30",
  name: "午餐",
  categoryId: 203,
  selectedTagIds: [301],
  businessTime: "2026-09-24T12:30:00+08:00",
  remark: "",
};

const response = {
  id: 4001,
  accountId: 1001,
  type: "EXPENSE",
  amount: "30.00",
  name: "午餐",
  displayName: "午餐",
  category: { id: 203, name: "午餐" },
  tags: [{ id: 301, name: "外卖" }],
  businessTime: "2026-10-02T08:00:00+08:00",
  remark: null,
};

describe("idempotency key", () => {
  it("encodes 16 random bytes as an opaque 32 character key", () => {
    const bytes = Uint8Array.from({ length: 16 }, (_, index) => index + 10);
    const encoded = randomBytesToIdempotencyKey(bytes);
    assert.equal(encoded, "0a0b0c0d0e0f10111213141516171819");
    assert.equal(encoded?.length, 32);
    assert.equal(encoded?.includes("1001"), false);
    assert.equal(encoded?.includes("EXPENSE"), false);
    assert.equal(randomBytesToIdempotencyKey(new Uint8Array(15)), null);
  });

  it("does not start a request when key generation fails", async () => {
    await assert.rejects(
      () =>
        createIdempotencyKey(async () => {
          throw new IdempotencyKeyError();
        }),
      IdempotencyKeyError,
    );
    const opened = await openSubmission(editingSubmission(), snapshot, async () => {
      throw new Error("random failed");
    });
    let requests = 0;
    if (opened.error.length === 0 && opened.state.phase === "submitting") {
      requests += 1;
    }
    assert.equal(requests, 0);
    assert.equal(opened.state.phase, "editing");
    assert.equal(opened.state.attempt, null);
    assert.equal(opened.error, PREPARE_MESSAGE);
  });
});

describe("transaction api", () => {
  it("posts the snapshot with an idempotency key and accepts 201 or 200", async () => {
    const requests: HttpTransportRequest[] = [];
    const api = apiFor((request) => {
      requests.push(request);
      return { statusCode: requests.length === 1 ? 201 : 200, data: response };
    });
    assert.equal(transactionCreatePath(1001), "/api/v1/accounts/1001/transactions");
    const createdTransaction = await api.createTransaction(snapshot, key);
    const replay = await api.createTransaction(snapshot, key);
    assert.equal(createdTransaction.id, 4001);
    assert.equal(replay.amount, "30.00");
    assert.equal(requests[0]?.url.endsWith("/api/v1/accounts/1001/transactions"), true);
    assert.equal(requests[0]?.header["Idempotency-Key"], key);
    assert.equal(requests[0]?.header.Authorization, "Bearer opaque-token");
    assert.equal(requests[0]?.data, JSON.stringify(createTransactionRequestBody(snapshot)));
    assert.equal(JSON.parse(requests[0]?.data ?? "{}").amount, "30");
  });

  it("rejects a response the client cannot confirm", async () => {
    const broken = apiFor(() => ({ statusCode: 201, data: { id: 1 } }));
    await assert.rejects(() => broken.createTransaction(snapshot, key), TransactionResponseError);
    const otherAccount = apiFor(() => ({ statusCode: 200, data: { ...response, accountId: 1002 } }));
    await assert.rejects(() => otherAccount.createTransaction(snapshot, key), TransactionResponseError);
    const otherType = apiFor(() => ({ statusCode: 201, data: { ...response, type: "INCOME" } }));
    await assert.rejects(() => otherType.createTransaction(snapshot, key), TransactionResponseError);
  });
});

describe("submission", () => {
  it("locks the form while an idempotency key is being prepared", async () => {
    assert.equal(interactionLocked("editing", false), false);
    assert.equal(canSave("editing", false, false), true);
    assert.equal(interactionLocked("editing", true), true);
    assert.equal(canSave("editing", false, true), false);
    assert.equal(interactionLocked("submitting", false), true);

    const ready = prepareSave("editing", false, form, 1001, created);
    assert.equal(ready.status, "ready");
    if (ready.status !== "ready") {
      return;
    }
    assert.equal(ready.snapshot.amount, "30");
    const acceptedAmount = interactionLocked("editing", true) ? ready.snapshot.amount : "100";
    assert.equal(acceptedAmount, "30");

    const opened = await openSubmission(editingSubmission(), ready.snapshot, async () => key);
    assert.equal(opened.state.phase, "submitting");
    assert.equal(opened.state.attempt?.snapshot.amount, "30");
    assert.equal(interactionLocked(opened.state.phase, false), true);

    const failed = await openSubmission(editingSubmission(), ready.snapshot, async () => {
      throw new Error("random failed");
    });
    assert.equal(failed.error, PREPARE_MESSAGE);
    assert.equal(failed.state.phase, "editing");
    assert.equal(failed.state.attempt, null);
    assert.equal(interactionLocked(failed.state.phase, false), false);
    assert.equal(canSave("editing", false, false), true);
  });

  it("submits once, keeps the attempt through uncertainty, and retries the same intent", () => {
    assert.equal(prepareSave("editing", true, form, 1001, created).status, "wait-tag");
    assert.equal(WAIT_TAG_MESSAGE, "请等待标签创建完成");
    const ready = prepareSave("editing", false, form, 1001, created);
    assert.equal(ready.status, "ready");
    if (ready.status !== "ready") {
      return;
    }
    const submitting = beginAttempt(editingSubmission(), ready.snapshot, key);
    assert.equal(submitting?.phase, "submitting");
    assert.equal(beginAttempt(submitting ?? editingSubmission(), ready.snapshot, "other-key"), null);
    assert.equal(canSave("submitting", false), false);

    const uncertain = applySubmitOutcome(submitting ?? editingSubmission(), classifySubmitError(new ApiError(0, "NETWORK_ERROR", "网络异常")));
    assert.equal(uncertain.phase, "uncertain");
    assert.equal(uncertain.error, UNCERTAIN_MESSAGE);
    assert.equal(applySubmitOutcome(submitting ?? editingSubmission(), classifySubmitError(new ApiError(500, "INTERNAL", "失败"))).phase, "uncertain");
    assert.equal(applySubmitOutcome(submitting ?? editingSubmission(), classifySubmitError(new TransactionResponseError())).phase, "uncertain");
    assert.equal(applySubmitOutcome(submitting ?? editingSubmission(), classifySubmitError(new ApiError(201, "HTTP_ERROR", "请求失败"))).phase, "uncertain");

    const changed: CreateTransactionForm = { ...form, amount: "99", categoryId: null, selectedTagIds: [], businessTime: "2026-11-01T00:00:00+08:00" };
    const retried = retryAttempt(uncertain);
    assert.equal(retried?.phase, "submitting");
    assert.equal(retried?.attempt?.idempotencyKey, key);
    assert.equal(retried?.attempt?.snapshot.amount, "30");
    assert.notEqual(retried?.attempt?.snapshot.amount, changed.amount);
    assert.equal(createTransactionRequestBody(retried?.attempt?.snapshot ?? snapshot).businessTime, snapshot.businessTime);

    const saved = applySubmitOutcome(retried ?? editingSubmission(), { kind: "success", transaction: view() });
    assert.equal(saved.phase, "success");
    assert.equal(saved.postSave?.targetMonth, "2026-10");
    assert.equal(saved.postSave?.transactionId, 4001);
    assert.equal(beginAttempt(saved, snapshot, "fresh"), null);
    assert.equal(canSave("success", false), false);
    assert.equal(formLocked("success"), true);
  });

  it("returns to editing after a definite failure and blocks an idempotency conflict", () => {
    const submitting = beginAttempt(editingSubmission(), snapshot, key) ?? editingSubmission();
    const invalid = applySubmitOutcome(submitting, classifySubmitError(new ApiError(400, "VALIDATION_FAILED", "后端原文")));
    assert.equal(invalid.phase, "editing");
    assert.equal(invalid.attempt, null);
    assert.equal(invalid.error, "请检查填写内容");

    const business = applySubmitOutcome(
      submitting,
      classifySubmitError(
        new ApiError(409, "BUSINESS_RULE_VIOLATION", "后端原文", "req", [{ field: "businessTime", reason: "BEFORE_ACCOUNT_CREATION" }]),
      ),
    );
    assert.equal(business.phase, "editing");
    assert.equal(business.attempt, null);
    assert.equal(business.error, "交易时间不得早于账户创建时间");
    assert.equal(applySubmitOutcome(submitting, classifySubmitError(new ApiError(409, "CATEGORY_NOT_BOOKABLE", "x"))).error, "所选分类当前不可用于记账，请重新选择");
    assert.equal(applySubmitOutcome(submitting, classifySubmitError(new ApiError(409, "CATEGORY_TYPE_MISMATCH", "x"))).error, "所选分类与交易类型不匹配，请重新选择");
    assert.equal(applySubmitOutcome(submitting, classifySubmitError(new ApiError(409, "TAG_TYPE_MISMATCH", "x"))).error, "所选标签与交易类型不匹配，请重新选择");
    assert.equal(applySubmitOutcome(submitting, classifySubmitError(new ApiError(404, "RESOURCE_NOT_FOUND", "x"))).error, "账户或关联数据已发生变化，请返回后重试");
    assert.equal(applySubmitOutcome(submitting, classifySubmitError(new ApiError(403, "FORBIDDEN", "x"))).error, "保存失败，请检查后重试");

    const conflict = applySubmitOutcome(submitting, classifySubmitError(new ApiError(409, "IDEMPOTENCY_CONFLICT", "后端原文")));
    assert.equal(conflict.phase, "conflict");
    assert.equal(conflict.attempt?.idempotencyKey, key);
    assert.equal(conflict.error, CONFLICT_MESSAGE);
    assert.equal(canSave("conflict", false), false);
    assert.equal(retryAttempt(conflict), null);

    const unauthorized = applySubmitOutcome(submitting, classifySubmitError(new ApiError(401, "SESSION_INVALID", "会话无效")));
    assert.equal(unauthorized.phase, "submitting");
    assert.equal(unauthorized.error, "");
    assert.equal(unauthorized.attempt?.idempotencyKey, key);
  });
});

function view(): TransactionView {
  return {
    id: 4001,
    accountId: 1001,
    type: "EXPENSE",
    amount: "30.00",
    name: "午餐",
    displayName: "午餐",
    category: { id: 203, name: "午餐" },
    tags: [{ id: 301, name: "外卖" }],
    businessTime: "2026-10-02T08:00:00+08:00",
    remark: null,
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
