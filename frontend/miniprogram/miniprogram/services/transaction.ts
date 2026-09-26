import type { HttpClient } from "../http/client";
import { updateTransactionRequestBody, type UpdateTransactionSnapshot } from "../transaction/edit";
import { createTransactionRequestBody, type CreateTransactionSnapshot } from "../transaction/form";
import { parseTransactionResponse, type TransactionView } from "../transaction/transaction";

export class TransactionResponseError extends Error {
  constructor(message = "交易响应无法识别") {
    super(message);
    this.name = "TransactionResponseError";
  }
}

export interface TransactionApi {
  createTransaction(snapshot: CreateTransactionSnapshot, idempotencyKey: string): Promise<TransactionView>;
  getTransaction(accountId: number, transactionId: number): Promise<TransactionView>;
  updateTransaction(snapshot: UpdateTransactionSnapshot): Promise<TransactionView>;
  deleteTransaction(accountId: number, transactionId: number): Promise<void>;
}

export function transactionCreatePath(accountId: number): string {
  return `/api/v1/accounts/${accountId}/transactions`;
}

export function transactionResourcePath(accountId: number, transactionId: number): string {
  return `/api/v1/accounts/${accountId}/transactions/${transactionId}`;
}

export function createTransactionApi(http: HttpClient): TransactionApi {
  return {
    async createTransaction(snapshot, idempotencyKey) {
      const body = await http.request<unknown>({
        method: "POST",
        path: transactionCreatePath(snapshot.accountId),
        body: createTransactionRequestBody(snapshot),
        authenticated: true,
        headers: { "Idempotency-Key": idempotencyKey },
      });
      const transaction = parseTransactionResponse(body);
      if (transaction === null || transaction.accountId !== snapshot.accountId || transaction.type !== snapshot.type) {
        throw new TransactionResponseError();
      }
      return transaction;
    },
    async getTransaction(accountId, transactionId) {
      const body = await http.request<unknown>({
        method: "GET",
        path: transactionResourcePath(accountId, transactionId),
        authenticated: true,
      });
      return requireTransaction(body, accountId, transactionId, null);
    },
    async updateTransaction(snapshot) {
      const body = await http.request<unknown>({
        method: "PUT",
        path: transactionResourcePath(snapshot.accountId, snapshot.transactionId),
        body: updateTransactionRequestBody(snapshot),
        authenticated: true,
      });
      return requireTransaction(body, snapshot.accountId, snapshot.transactionId, snapshot.fixedType);
    },
    async deleteTransaction(accountId, transactionId) {
      await http.request<void>({
        method: "DELETE",
        path: transactionResourcePath(accountId, transactionId),
        authenticated: true,
      });
    },
  };
}

function requireTransaction(
  body: unknown,
  accountId: number,
  transactionId: number,
  fixedType: CreateTransactionSnapshot["type"] | null,
): TransactionView {
  const transaction = parseTransactionResponse(body);
  if (
    transaction === null ||
    transaction.accountId !== accountId ||
    transaction.id !== transactionId ||
    (fixedType !== null && transaction.type !== fixedType)
  ) {
    throw new TransactionResponseError();
  }
  return transaction;
}
