import { parseAdjustmentCreateResult, parseAdjustmentDetail, type AdjustmentCreateResult, type AdjustmentRecord } from "../balance-adjustment/contract";
import { adjustmentRequestBody, BalanceAdjustmentResponseError, type BalanceAdjustmentSnapshot } from "../balance-adjustment/submission";
import type { HttpClient } from "../http/client";

export interface BalanceAdjustmentApi {
  createAdjustment(snapshot: BalanceAdjustmentSnapshot, idempotencyKey: string): Promise<AdjustmentCreateResult>;
  getAdjustment(accountId: number, adjustmentId: number): Promise<AdjustmentRecord>;
  deleteAdjustment(accountId: number, adjustmentId: number): Promise<void>;
}

export function adjustmentCollectionPath(accountId: number): string {
  return `/api/v1/accounts/${accountId}/balance-adjustments`;
}

export function adjustmentResourcePath(accountId: number, adjustmentId: number): string {
  return `/api/v1/accounts/${accountId}/balance-adjustments/${adjustmentId}`;
}

export function createBalanceAdjustmentApi(http: HttpClient): BalanceAdjustmentApi {
  return {
    async createAdjustment(snapshot, idempotencyKey) {
      const body = await http.request<unknown>({
        method: "POST",
        path: adjustmentCollectionPath(snapshot.accountId),
        body: adjustmentRequestBody(snapshot),
        authenticated: true,
        headers: { "Idempotency-Key": idempotencyKey },
      });
      const result = parseAdjustmentCreateResult(body, snapshot.targetBalance);
      if (result === null) {
        throw new BalanceAdjustmentResponseError();
      }
      return result;
    },
    async getAdjustment(accountId, adjustmentId) {
      const body = await http.request<unknown>({
        method: "GET",
        path: adjustmentResourcePath(accountId, adjustmentId),
        authenticated: true,
      });
      const adjustment = parseAdjustmentDetail(body, adjustmentId);
      if (adjustment === null) {
        throw new BalanceAdjustmentResponseError();
      }
      return adjustment;
    },
    async deleteAdjustment(accountId, adjustmentId) {
      await http.request<unknown>({
        method: "DELETE",
        path: adjustmentResourcePath(accountId, adjustmentId),
        authenticated: true,
      });
    },
  };
}
