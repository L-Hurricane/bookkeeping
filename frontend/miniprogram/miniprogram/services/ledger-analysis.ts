import { parseLedgerAnalysis, type LedgerAnalysis } from "../analysis/ledger-contract";
import { AnalysisResponseError } from "../analysis/screen";
import type { HttpClient } from "../http/client";

export interface LedgerAnalysisApi {
  load(periodKind: "MONTH" | "YEAR", period: string): Promise<LedgerAnalysis>;
  generate(periodKind: "MONTH" | "YEAR", period: string, expectedFingerprint: string): Promise<{
    outcome: string;
    analysis: LedgerAnalysis;
  }>;
}

export function createLedgerAnalysisApi(http: HttpClient): LedgerAnalysisApi {
  return {
    async load(periodKind, period) {
      const body = await http.request<unknown>({
        method: "GET",
        path: `/api/v1/ledger-analysis?periodKind=${periodKind}&period=${encodeURIComponent(period)}`,
        authenticated: true,
      });
      const analysis = parseLedgerAnalysis(body, periodKind, period);
      if (analysis === null) throw new AnalysisResponseError();
      return analysis;
    },
    async generate(periodKind, period, expectedFingerprint) {
      const body = await http.request<unknown>({
        method: "POST",
        path: "/api/v1/ledger-analysis",
        body: { periodKind, period, expectedFingerprint },
        authenticated: true,
      });
      if (typeof body !== "object" || body === null) throw new AnalysisResponseError();
      const record = body as Record<string, unknown>;
      if (typeof record.outcome !== "string") throw new AnalysisResponseError();
      const analysis = parseLedgerAnalysis(record.analysis, periodKind, period);
      if (analysis === null) throw new AnalysisResponseError();
      return { outcome: record.outcome, analysis };
    },
  };
}
