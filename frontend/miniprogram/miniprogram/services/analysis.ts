import { parseGenerateResult, parseMonthlyAnalysis, type GenerateMonthlyAnalysisResult, type MonthlyAnalysis } from "../analysis/contract";
import { AnalysisResponseError } from "../analysis/screen";
import type { HttpClient } from "../http/client";

export interface MonthlyAnalysisApi {
  load(accountId: number, month: string): Promise<MonthlyAnalysis>;
  generate(accountId: number, month: string, expectedFingerprint: string): Promise<GenerateMonthlyAnalysisResult>;
}

export function createMonthlyAnalysisApi(http: HttpClient): MonthlyAnalysisApi {
  return {
    async load(accountId, month) {
      const body = await http.request<unknown>({
        method: "GET",
        path: monthlyAnalysisPath(accountId, month),
        authenticated: true,
      });
      const analysis = parseMonthlyAnalysis(body, month);
      if (analysis === null) {
        throw new AnalysisResponseError();
      }
      return analysis;
    },
    async generate(accountId, month, expectedFingerprint) {
      const body = await http.request<unknown>({
        method: "POST",
        path: monthlyAnalysisResource(accountId),
        body: { month, expectedFingerprint },
        authenticated: true,
      });
      const result = parseGenerateResult(body, month);
      if (result === null) {
        throw new AnalysisResponseError();
      }
      return result;
    },
  };
}

function monthlyAnalysisResource(accountId: number): string {
  return `/api/v1/accounts/${accountId}/monthly-analysis`;
}

export function monthlyAnalysisPath(accountId: number, month: string): string {
  return `${monthlyAnalysisResource(accountId)}?month=${encodeURIComponent(month)}`;
}
