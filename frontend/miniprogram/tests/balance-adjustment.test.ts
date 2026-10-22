import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  adjustmentDetailAccess,
  adjustmentDetailView,
  classifyAdjustmentDelete,
  confirmAdjustmentDelete,
} from "../miniprogram/balance-adjustment/detail";
import { canonicalMoney, subtractMoneyStrings } from "../miniprogram/balance-adjustment/money";
import { previewAdjustment } from "../miniprogram/balance-adjustment/preview";
import {
  ADJUSTMENT_CONFLICT_MESSAGE,
  BalanceAdjustmentResponseError,
  applyAdjustmentFailure,
  applyAdjustmentResult,
  beginAdjustment,
  buildAdjustmentSnapshot,
  canSaveAdjustment,
  editingAdjustment,
  handoffCreatedAdjustment,
  openAdjustment,
  retryAdjustment,
  type BalanceAdjustmentSnapshot,
} from "../miniprogram/balance-adjustment/submission";
import { ApiError, createHttpClient, type HttpTransportRequest } from "../miniprogram/http/client";
import { saveSession } from "../miniprogram/session/session";
import { adjustmentCollectionPath, adjustmentResourcePath, createBalanceAdjustmentApi } from "../miniprogram/services/balance-adjustment";
import type { KeyValueStorage } from "../miniprogram/storage/key-value";

const time = "2026-09-26T16:00:00+08:00";
const createdTime = "2026-08-01T00:00:00+08:00";
const snapshot: BalanceAdjustmentSnapshot = {
  accountId: 1001,
  targetBalance: "100.00",
  businessTime: time,
  remark: null,
};

describe("adjustment money", () => {
  it("accepts a signed target and rejects an empty or over-precise value", () => {
    assert.equal(canonicalMoney("0"), "0.00");
    assert.equal(canonicalMoney("100"), "100.00");
    assert.equal(canonicalMoney("-100"), "-100.00");
    assert.equal(canonicalMoney("+100.50"), "100.50");
    assert.equal(canonicalMoney(""), null);
    assert.equal(canonicalMoney("1.234"), null);
    assert.equal(canonicalMoney("abc"), null);
  });

  it("subtracts decimal strings without using binary floating point", () => {
    assert.equal(subtractMoneyStrings("100.00", "65.50"), "34.50");
    assert.equal(subtractMoneyStrings("60.50", "65.50"), "-5.00");
    assert.equal(subtractMoneyStrings("0.00", "100.00"), "-100.00");
    assert.equal(subtractMoneyStrings("-50.00", "-100.00"), "50.00");
    assert.equal(subtractMoneyStrings("-100.00", "50.00"), "-150.00");
    assert.equal(subtractMoneyStrings("9007199254740993.15", "0.01"), "9007199254740993.14");
    assert.equal(subtractMoneyStrings("99999999999999999.99", "0.01"), "99999999999999999.98");
  });

  it("previews the exact difference and disables a zero adjustment", () => {
    assert.equal(difference("100.00", "0"), "-100.00");
    assert.equal(difference("-100.00", "0"), "+100.00");
    assert.equal(difference("100.00", "-50"), "-150.00");
    assert.equal(adjustmentAmount("1300.00", "1200"), "-100.00");
    assert.equal(adjustmentAmount("1300.00", "1200.00"), "-100.00");
    const zero = previewAdjustment("60.50", "60.5");
    assert.equal(zero.ok, true);
    if (!zero.ok) {
      return;
    }
    assert.equal(zero.preview.difference, "0.00");
    assert.equal(canSaveAdjustment("editing", false, zero.preview.zero, true), false);
    assert.equal(canSaveAdjustment("submitting", false, false, true), false);
    assert.equal(canSaveAdjustment("editing", true, false, true), false);
  });
});

describe("adjustment create api", () => {
  it("posts the target without an adjustment amount and accepts both created results", async () => {
    assert.equal(adjustmentCollectionPath(1001), "/api/v1/accounts/1001/balance-adjustments");
    const created = apiFor((request) => {
      assert.equal(request.header["Idempotency-Key"], "abc");
      const body = JSON.parse(String(request.data)) as Record<string, unknown>;
      assert.equal(body.adjustmentAmount, undefined);
      assert.equal(body.currentBalance, undefined);
      assert.equal(body.targetBalance, "60.50");
      return { statusCode: request.header["Idempotency-Key"] === "abc" ? 201 : 200, data: createdBody("60.50") };
    });
    const first = await created.createAdjustment({ ...snapshot, targetBalance: "60.50" }, "abc");
    assert.equal(first.created, true);
    const replay = apiFor(() => ({ statusCode: 200, data: createdBody("60.50") }));
    const second = await replay.createAdjustment({ ...snapshot, targetBalance: "60.50" }, "abc");
    assert.equal(second.created, true);
    const unchanged = apiFor(() => ({ statusCode: 200, data: noChangeBody("60.50") }));
    const third = await unchanged.createAdjustment({ ...snapshot, targetBalance: "60.50" }, "abc");
    assert.equal(third.created, false);
  });

  it("rejects a malformed union and a created response whose balances disagree", async () => {
    const broken = apiFor(() => ({ statusCode: 200, data: { created: true, targetBalance: "60.50", adjustment: null } }));
    await assert.rejects(() => broken.createAdjustment({ ...snapshot, targetBalance: "60.50" }, "abc"), BalanceAdjustmentResponseError);
    const mismatch = apiFor(() => ({ statusCode: 201, data: { ...createdBody("60.50"), currentBalanceAfter: "70.00" } }));
    await assert.rejects(() => mismatch.createAdjustment({ ...snapshot, targetBalance: "60.50" }, "abc"), BalanceAdjustmentResponseError);
    const consistent = apiFor(() => ({ statusCode: 201, data: amountBody("100.00", "80.00", "-20.00") }));
    const checked = await consistent.createAdjustment({ ...snapshot, targetBalance: "80.00" }, "abc");
    assert.equal(checked.created && checked.adjustment.adjustmentAmount, "-20.00");
    const inconsistent = apiFor(() => ({ statusCode: 201, data: amountBody("100.00", "80.00", "-30.00") }));
    await assert.rejects(() => inconsistent.createAdjustment({ ...snapshot, targetBalance: "80.00" }, "abc"), BalanceAdjustmentResponseError);
    const matched = apiFor(() => ({ statusCode: 200, data: noChangeBody("60.00") }));
    assert.equal((await matched.createAdjustment({ ...snapshot, targetBalance: "60.00" }, "abc")).created, false);
    const drifted = apiFor(() => ({ statusCode: 200, data: { ...noChangeBody("60.00"), currentBalance: "70.00" } }));
    await assert.rejects(() => drifted.createAdjustment({ ...snapshot, targetBalance: "60.00" }, "abc"), BalanceAdjustmentResponseError);
    const spelled = apiFor(() => ({ statusCode: 201, data: { ...createdBody("60.50"), targetBalance: "60.5", currentBalanceAfter: "60.5" } }));
    const accepted = await spelled.createAdjustment({ ...snapshot, targetBalance: "60.50" }, "abc");
    assert.equal(accepted.created && accepted.targetBalance, "60.50");
  });
});

describe("adjustment create state", () => {
  it("locks while preparing, retries the same attempt, and treats no-change as editing", async () => {
    const editing = editingAdjustment("1300.00");
    const failed = await openAdjustment(editing, snapshot, async () => {
      throw new Error("key");
    });
    assert.equal(failed.state.phase, "editing");
    assert.equal(failed.state.attempt, null);
    const opened = await openAdjustment(editing, snapshot, async () => "key-a");
    assert.equal(opened.state.phase, "submitting");
    assert.equal(beginAdjustment(opened.state, snapshot, "other"), null);
    const uncertain = applyAdjustmentFailure(opened.state, "uncertain", new ApiError(500, "INTERNAL", "失败"));
    assert.equal(uncertain.phase, "uncertain");
    const retried = retryAdjustment(uncertain);
    assert.equal(retried?.attempt?.idempotencyKey, "key-a");
    assert.equal(retried?.attempt?.snapshot.targetBalance, "100.00");
    const saved = applyAdjustmentResult(retried ?? editing, {
      created: true,
      currentBalanceBefore: "1300.00",
      targetBalance: "100.00",
      adjustment: { id: 5001, adjustmentAmount: "-1200.00", businessTime: "2026-08-15T08:00:00+08:00", remark: null },
      currentBalanceAfter: "100.00",
    });
    assert.equal(saved.postSave?.targetMonth, "2026-08");
    assert.equal(saved.postSave?.kind, "adjustment");
    const conflict = applyAdjustmentFailure(opened.state, "conflict", new ApiError(409, "IDEMPOTENCY_CONFLICT", "冲突"));
    assert.equal(conflict.phase, "conflict");
    assert.equal(conflict.error, ADJUSTMENT_CONFLICT_MESSAGE);
    assert.equal(conflict.attempt?.idempotencyKey, "key-a");
  });

  it("keeps a no-change replay on the original key and starts a new key after the target changes", () => {
    let state = beginAdjustment(editingAdjustment("90.00"), snapshot, "key-a");
    assert.equal(state?.phase, "submitting");
    state = applyAdjustmentFailure(state ?? editingAdjustment("90.00"), "uncertain", new ApiError(0, "NETWORK_ERROR", "网络异常"));
    state = retryAdjustment(state) ?? state;
    assert.equal(state.attempt?.snapshot.targetBalance, "100.00");
    assert.equal(state.attempt?.idempotencyKey, "key-a");
    state = applyAdjustmentResult(state, {
      created: false,
      reason: "NO_ADJUSTMENT_NEEDED",
      currentBalance: "100.00",
      targetBalance: "100.00",
    });
    assert.equal(state.phase, "editing");
    assert.equal(state.attempt, null);
    assert.equal(state.error, "");
    assert.equal(state.currentBalance, "100.00");
    assert.equal(state.confirmedTarget, state.currentBalance);
    assert.equal(handoffCreatedAdjustment(state.phase, state.postSave, false).result, null);
    const preview = previewAdjustment(state.currentBalance, state.confirmedTarget ?? "");
    assert.equal(preview.ok, true);
    if (preview.ok) {
      assert.equal(preview.preview.zero, true);
    }
    const next = beginAdjustment(state, { ...snapshot, targetBalance: "90.00" }, "key-b");
    assert.equal(next?.attempt?.idempotencyKey, "key-b");
    assert.equal(next?.attempt?.snapshot.targetBalance, "90.00");
    const early = buildAdjustmentSnapshot("90", "2026-07-01T00:00:00+08:00", "", 1001, createdTime);
    assert.equal(early.ok, true);
  });
});

describe("adjustment detail", () => {
  it("reads one adjustment and shows the fixed amount without an edit action", async () => {
    assert.equal(adjustmentResourcePath(1001, 5001), "/api/v1/accounts/1001/balance-adjustments/5001");
    const api = apiFor(() => ({
      statusCode: 200,
      data: { id: 5001, adjustmentAmount: "-5.00", businessTime: "2026-09-30T23:59:59+08:00", remark: null },
    }));
    const view = adjustmentDetailView(await api.getAdjustment(1001, 5001));
    assert.equal(view.amountLabel, "-¥5.00");
    assert.equal(view.businessTimeLabel, "2026-09-30 23:59:59");
    assert.equal(view.remarkLabel, "—");
    assert.equal(adjustmentDetailAccess("idle").edit, false);
    assert.equal(adjustmentDetailAccess("idle").delete, true);
    const mismatched = apiFor(() => ({ statusCode: 200, data: { id: 9, adjustmentAmount: "-5.00", businessTime: time, remark: "月末核对" } }));
    await assert.rejects(() => mismatched.getAdjustment(1001, 5001), BalanceAdjustmentResponseError);
    const missing = apiFor(() => ({ statusCode: 404, data: { error: { code: "RESOURCE_NOT_FOUND", message: "不存在" } } }));
    await assert.rejects(() => missing.getAdjustment(1001, 5001), (error: unknown) => error instanceof ApiError && error.status === 404);
  });

  it("confirms one delete and treats absence or uncertainty without opening edit", async () => {
    assert.equal(confirmAdjustmentDelete(false), "cancel");
    assert.equal(confirmAdjustmentDelete(true), "send");
    assert.deepEqual(adjustmentDetailAccess("deleting"), { edit: false, delete: false, retry: false });
    assert.deepEqual(adjustmentDetailAccess("uncertain"), { edit: false, delete: false, retry: true });
    assert.equal(classifyAdjustmentDelete(new ApiError(0, "NETWORK_ERROR", "网络异常")), "uncertain");
    assert.equal(classifyAdjustmentDelete(new ApiError(500, "INTERNAL", "失败")), "uncertain");
    assert.equal(classifyAdjustmentDelete(new ApiError(404, "RESOURCE_NOT_FOUND", "不存在")), "absent");
    assert.equal(classifyAdjustmentDelete(new ApiError(401, "SESSION_INVALID", "会话无效")), "unauthorized");
    assert.equal(classifyAdjustmentDelete(new ApiError(403, "FORBIDDEN", "拒绝")), "definite");
    let calls = 0;
    const api = apiFor(() => {
      calls += 1;
      return { statusCode: calls === 1 ? 204 : 404, data: calls === 1 ? "" : { error: { code: "RESOURCE_NOT_FOUND", message: "不存在" } } };
    });
    if (confirmAdjustmentDelete(false) === "send") {
      await api.deleteAdjustment(1001, 5001);
    }
    assert.equal(calls, 0);
    await api.deleteAdjustment(1001, 5001);
    await assert.rejects(() => api.deleteAdjustment(1001, 5001), (error: unknown) => classifyAdjustmentDelete(error) === "absent");
    assert.equal(calls, 2);
  });
});

describe("adjustment statistics isolation", () => {
  it("changes current balance without changing the monthly transaction summary", () => {
    const summary = { transactionCount: 3, netIncome: "200.00", netExpense: "15.50", surplus: "184.50" };
    const before = { currentBalance: "1300.00", summary };
    const delta = subtractMoneyStrings("1200.00", before.currentBalance);
    const created = { currentBalance: "1200.00", summary: before.summary };
    assert.equal(delta, "-100.00");
    assert.equal(created.currentBalance, "1200.00");
    assert.deepEqual(created.summary, summary);
    assert.equal(subtractMoneyStrings(created.currentBalance, delta ?? "0.00"), before.currentBalance);
    assert.deepEqual(created.summary, summary);
  });
});

function difference(current: string, target: string): string {
  const preview = previewAdjustment(current, target);
  return preview.ok ? preview.preview.difference : "";
}

function adjustmentAmount(current: string, target: string): string {
  const preview = previewAdjustment(current, target);
  return preview.ok ? preview.preview.adjustmentAmount : "";
}

function amountBody(before: string, target: string, adjustmentAmount: string): Record<string, unknown> {
  return {
    created: true,
    reason: null,
    currentBalanceBefore: before,
    targetBalance: target,
    adjustment: { id: 5001, adjustmentAmount, businessTime: "2026-08-15T08:00:00+08:00", remark: null },
    currentBalanceAfter: target,
    currentBalance: null,
  };
}

function createdBody(target: string): Record<string, unknown> {
  return {
    created: true,
    reason: null,
    currentBalanceBefore: "65.50",
    targetBalance: target,
    adjustment: { id: 5001, adjustmentAmount: "-5.00", businessTime: "2026-08-15T08:00:00+08:00", remark: null },
    currentBalanceAfter: target,
    currentBalance: null,
  };
}

function noChangeBody(target: string): Record<string, unknown> {
  return {
    created: false,
    reason: "NO_ADJUSTMENT_NEEDED",
    currentBalanceBefore: null,
    targetBalance: target,
    adjustment: null,
    currentBalanceAfter: null,
    currentBalance: target,
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
  return createBalanceAdjustmentApi(
    createHttpClient({
      baseUrl: "http://127.0.0.1:8080",
      storage,
      now: () => new Date("2026-09-25T08:00:00Z"),
      onUnauthorized: () => undefined,
      transport: async (request) => responder(request),
    }),
  );
}
