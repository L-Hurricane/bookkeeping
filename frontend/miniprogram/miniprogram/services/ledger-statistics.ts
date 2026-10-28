import type { HttpClient } from "../http/client";
import {
  parseLedgerReport,
  parseLedgerTransactions,
  parseTagAnalysis,
  statisticsReportPath,
  statisticsTransactionPath,
  tagAnalysisPath,
  type LedgerDetailContext,
  type LedgerQuery,
  type LedgerReport,
  type LedgerTransactionList,
  type TagAnalysis,
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
  loadTagAnalysis(context: LedgerDetailContext): Promise<TagAnalysis>;
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
    async loadTagAnalysis(context) {
      const path = tagAnalysisPath(context);
      if (path.length === 0) throw new LedgerStatisticsResponseError();
      const body = await http.request<unknown>({ method: "GET", path, authenticated: true });
      const analysis = parseTagAnalysis(body);
      if (analysis === null) throw new LedgerStatisticsResponseError();
      return analysis;
    },
  };
}
