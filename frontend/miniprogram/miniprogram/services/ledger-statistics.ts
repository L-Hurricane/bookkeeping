import type { HttpClient } from "../http/client";
import {
  parseLedgerReport,
  parseLedgerTransactions,
  statisticsReportPath,
  statisticsTransactionPath,
  type LedgerDetailContext,
  type LedgerQuery,
  type LedgerReport,
  type LedgerTransactionList,
} from "../statistics/ledger-contract";

export class LedgerStatisticsResponseError extends Error {
  constructor() {
    super("统计响应无法识别");
    this.name = "LedgerStatisticsResponseError";
  }
}

export interface LedgerStatisticsApi {
  load(query: LedgerQuery): Promise<LedgerReport>;
  listTransactions(context: LedgerDetailContext): Promise<LedgerTransactionList>;
}

export function createLedgerStatisticsApi(http: HttpClient): LedgerStatisticsApi {
  return {
    async load(query) {
      const body = await http.request<unknown>({ method: "GET", path: statisticsReportPath(query), authenticated: true });
      const report = parseLedgerReport(body);
      if (report === null) throw new LedgerStatisticsResponseError();
      return report;
    },
    async listTransactions(context) {
      const body = await http.request<unknown>({ method: "GET", path: statisticsTransactionPath(context), authenticated: true });
      const list = parseLedgerTransactions(body);
      if (list === null) throw new LedgerStatisticsResponseError();
      return list;
    },
  };
}
