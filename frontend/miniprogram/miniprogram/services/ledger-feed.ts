import type { HttpClient } from "../http/client";
import { ledgerFeedPath, parseLedgerFeed, type LedgerFeedPage, type LedgerFeedQuery } from "../ledger/feed-contract";

export class LedgerFeedResponseError extends Error {
  constructor() {
    super("流水响应无法识别");
    this.name = "LedgerFeedResponseError";
  }
}

export interface LedgerFeedApi {
  load(query: LedgerFeedQuery): Promise<LedgerFeedPage>;
}

export function createLedgerFeedApi(http: HttpClient): LedgerFeedApi {
  return {
    async load(query) {
      const body = await http.request<unknown>({ method: "GET", path: ledgerFeedPath(query), authenticated: true });
      const page = parseLedgerFeed(body);
      if (page === null) throw new LedgerFeedResponseError();
      return page;
    },
  };
}
