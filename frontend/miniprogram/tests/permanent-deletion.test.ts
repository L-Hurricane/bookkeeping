import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  affectedAccountRows,
  canConfirmPermanentDeletion,
  classifyPermanentDeletionExecute,
  isPermanentDeletionExecuteSuccess,
  mayEnterPermanentDeletion,
  parsePermanentDeletionPreview,
  permanentDeletionExecuteErrorMessage,
} from "../miniprogram/account/permanent-deletion";
import { moneyToneClass } from "../miniprogram/account/money-tone";
import { ApiError } from "../miniprogram/http/client";
import { createHttpClient, type HttpTransportRequest, type HttpTransportResponse } from "../miniprogram/http/client";
import { saveSession } from "../miniprogram/session/session";
import { AccountResponseError, createAccountApi } from "../miniprogram/services/account";
import type { KeyValueStorage } from "../miniprogram/storage/key-value";

const previewBody = {
  transactionCount: 2,
  adjustmentCount: 1,
  transferCount: 1,
  analysisRecordCount: 0,
  affectedAccounts: [
    {
      accountId: 1002,
      name: "现金",
      balanceBefore: "500.00",
      balanceAfter: "300.00",
      change: "-200.00",
    },
  ],
  previewToken: "preview-token-abc",
};

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

function apiFor(responder: (request: HttpTransportRequest) => HttpTransportResponse) {
  const storage = memoryStorage();
  saveSession(storage, { token: "opaque-token", expiresAt: "2099-01-01T00:00:00Z" });
  const http = createHttpClient({
    baseUrl: "http://127.0.0.1:8080",
    storage,
    now: () => new Date("2026-09-25T08:00:00Z"),
    onUnauthorized: () => undefined,
    transport: async (request) => responder(request),
  });
  return createAccountApi(http);
}

describe("permanent deletion preview", () => {
  it("parses preview counts, counterpart balances, and token", () => {
    const preview = parsePermanentDeletionPreview(previewBody);
    assert.notEqual(preview, null);
    assert.equal(preview?.transactionCount, 2);
    assert.equal(preview?.adjustmentCount, 1);
    assert.equal(preview?.transferCount, 1);
    assert.equal(preview?.previewToken, "preview-token-abc");
    assert.equal(preview?.affectedAccounts[0]?.change, "-200.00");

    assert.equal(parsePermanentDeletionPreview({ ...previewBody, previewToken: "" }), null);
    assert.equal(parsePermanentDeletionPreview({ ...previewBody, affectedAccounts: [{ accountId: 1 }] }), null);
  });

  it("maps affected account money colors from server strings", () => {
    const preview = parsePermanentDeletionPreview(previewBody);
    assert.notEqual(preview, null);
    const rows = affectedAccountRows(preview!);
    assert.equal(rows[0]?.changeLabel, "¥-200.00");
    assert.equal(rows[0]?.changeToneClass, moneyToneClass("-200.00"));
    assert.equal(rows[0]?.balanceBeforeToneClass, moneyToneClass("500.00"));
  });

  it("blocks confirm when analysis records remain", () => {
    const blocked = parsePermanentDeletionPreview({ ...previewBody, analysisRecordCount: 2 });
    assert.notEqual(blocked, null);
    assert.equal(canConfirmPermanentDeletion(blocked!.analysisRecordCount, false), false);
    assert.equal(canConfirmPermanentDeletion(0, false), true);
    assert.equal(canConfirmPermanentDeletion(0, true), false);
  });

  it("rejects non-archived entry", () => {
    assert.equal(mayEnterPermanentDeletion("ARCHIVED"), true);
    assert.equal(mayEnterPermanentDeletion("ACTIVE"), false);
  });
});

describe("permanent deletion execute errors", () => {
  it("does not treat conflict or missing responses as success", () => {
    assert.equal(isPermanentDeletionExecuteSuccess("success"), true);
    assert.equal(isPermanentDeletionExecuteSuccess("preview_stale"), false);
    assert.equal(isPermanentDeletionExecuteSuccess("analysis_records_remain"), false);
    assert.equal(isPermanentDeletionExecuteSuccess("account_not_archived"), false);
    assert.equal(isPermanentDeletionExecuteSuccess("not_found"), false);

    assert.equal(
      classifyPermanentDeletionExecute(new ApiError(409, "PREVIEW_STALE", "删除影响已变化，请重新预览并确认")),
      "preview_stale",
    );
    assert.equal(
      classifyPermanentDeletionExecute(new ApiError(409, "ANALYSIS_RECORDS_REMAIN", "该账户仍有分析记录，本次未删除任何数据")),
      "analysis_records_remain",
    );
    assert.equal(
      classifyPermanentDeletionExecute(new ApiError(409, "ACCOUNT_NOT_ARCHIVED", "只有已归档账户可以永久删除")),
      "account_not_archived",
    );
    assert.equal(classifyPermanentDeletionExecute(new ApiError(404, "RESOURCE_NOT_FOUND", "资源不存在")), "not_found");
    assert.equal(permanentDeletionExecuteErrorMessage("preview_stale"), "删除影响已变化，请重新预览并确认");
  });
});

describe("permanent deletion api", () => {
  it("submits the preview token from the latest preview on execute", async () => {
    let executeBody = "";
    const accounts = apiFor((request) => {
      if (request.method === "POST" && request.url.endsWith("/permanent-deletion/preview")) {
        return { statusCode: 200, data: previewBody };
      }
      if (request.method === "POST" && request.url.endsWith("/permanent-deletion")) {
        executeBody = request.data ?? "";
        return { statusCode: 204, data: "" };
      }
      return { statusCode: 500, data: { error: { code: "INTERNAL_ERROR", message: "unexpected" } } };
    });
    const preview = await accounts.previewPermanentDeletion(1001);
    await accounts.executePermanentDeletion(1001, preview.previewToken);
    assert.equal(executeBody, JSON.stringify({ previewToken: "preview-token-abc" }));
  });

  it("rejects malformed preview responses", async () => {
    const accounts = apiFor(() => ({ statusCode: 200, data: { transactionCount: "2" } }));
    await assert.rejects(
      () => accounts.previewPermanentDeletion(1001),
      (error: unknown) => error instanceof AccountResponseError,
    );
  });
});
