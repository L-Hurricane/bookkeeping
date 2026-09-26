import type { HttpClient } from "../http/client";
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
}

export function transactionCreatePath(accountId: number): string {
  return `/api/v1/accounts/${accountId}/transactions`;
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
  };
}
