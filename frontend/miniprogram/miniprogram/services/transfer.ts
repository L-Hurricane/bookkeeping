import type { HttpClient } from "../http/client";
import { parseTransferView, type TransferView } from "../transfer/contract";
import { transferRequestBody, TransferResponseError, type TransferSnapshot } from "../transfer/submission";

export interface TransferApi {
  createTransfer(snapshot: TransferSnapshot, idempotencyKey: string): Promise<TransferView>;
  getTransfer(transferId: number): Promise<TransferView>;
  updateTransfer(transferId: number, snapshot: TransferSnapshot): Promise<TransferView>;
  deleteTransfer(transferId: number): Promise<void>;
}

export function transferCollectionPath(): string {
  return "/api/v1/transfers";
}

export function transferResourcePath(transferId: number): string {
  return `/api/v1/transfers/${transferId}`;
}

export function createTransferApi(http: HttpClient): TransferApi {
  return {
    async createTransfer(snapshot, idempotencyKey) {
      const body = await http.request<unknown>({
        method: "POST",
        path: transferCollectionPath(),
        body: transferRequestBody(snapshot),
        authenticated: true,
        headers: { "Idempotency-Key": idempotencyKey },
      });
      const transfer = parseTransferView(body);
      if (transfer === null || !matchesSnapshot(transfer, snapshot)) {
        throw new TransferResponseError();
      }
      return transfer;
    },
    async getTransfer(transferId) {
      const body = await http.request<unknown>({
        method: "GET",
        path: transferResourcePath(transferId),
        authenticated: true,
      });
      const transfer = parseTransferView(body);
      if (transfer === null || transfer.id !== transferId) {
        throw new TransferResponseError();
      }
      return transfer;
    },
    async updateTransfer(transferId, snapshot) {
      const body = await http.request<unknown>({
        method: "PUT",
        path: transferResourcePath(transferId),
        body: transferRequestBody(snapshot),
        authenticated: true,
      });
      const transfer = parseTransferView(body);
      if (transfer === null || transfer.id !== transferId || !matchesSnapshot(transfer, snapshot)) {
        throw new TransferResponseError();
      }
      return transfer;
    },
    async deleteTransfer(transferId) {
      await http.request<void>({
        method: "DELETE",
        path: transferResourcePath(transferId),
        authenticated: true,
      });
    },
  };
}

function matchesSnapshot(transfer: TransferView, snapshot: TransferSnapshot): boolean {
  return (
    transfer.fromAccountId === snapshot.fromAccountId &&
    transfer.toAccountId === snapshot.toAccountId &&
    transfer.amount === snapshot.amount &&
    transfer.fromBusinessTime === snapshot.fromBusinessTime &&
    transfer.toBusinessTime === snapshot.toBusinessTime
  );
}
