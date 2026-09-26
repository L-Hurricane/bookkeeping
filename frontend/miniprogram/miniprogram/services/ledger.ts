import { ApiError, type HttpClient } from "../http/client";
import { ledgerPath, parseLedger, type Ledger } from "../ledger/ledger";

export class LedgerResponseError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "LedgerResponseError";
  }
}

export interface LedgerApi {
  getLedger(accountId: number, month: string): Promise<Ledger>;
}

export function createLedgerApi(http: HttpClient): LedgerApi {
  return {
    async getLedger(accountId, month) {
      const body = await http.request<unknown>({
        method: "GET",
        path: ledgerPath(accountId, month),
        authenticated: true,
      });
      const ledger = parseLedger(body);
      if (ledger === null || ledger.accountId !== accountId || ledger.month !== month) {
        throw new LedgerResponseError("流水响应无法识别");
      }
      return ledger;
    },
  };
}

export function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401;
}
