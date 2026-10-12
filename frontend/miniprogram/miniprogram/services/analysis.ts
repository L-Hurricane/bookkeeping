import { parseMonthlyAnalysis, type MonthlyAnalysis } from "../analysis/contract";
import { AnalysisResponseError } from "../analysis/screen";
import type { HttpClient } from "../http/client";

export interface MonthlyAnalysisApi {
  load(accountId: number, month: string): Promise<MonthlyAnalysis>;
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
  };
}

export function monthlyAnalysisPath(accountId: number, month: string): string {
  return `/api/v1/accounts/${accountId}/monthly-analysis?month=${encodeURIComponent(month)}`;
}
