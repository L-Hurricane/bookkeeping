import { isAnalysisMonth } from "./contract";

export interface AnalysisPageContext {
  readonly accountId: number;
  readonly month: string;
}

export function monthlyAnalysisPagePath(accountId: number, month: string): string {
  return `/pages/monthly-analysis/index?accountId=${accountId}&month=${encodeURIComponent(month)}`;
}

export function ledgerAnalysisPagePath(periodKind: "MONTH" | "YEAR", period: string): string {
  return `/pages/monthly-analysis/index?scope=ledger&periodKind=${periodKind}&period=${encodeURIComponent(period)}`;
}

export function parseAnalysisContext(query: Record<string, string | undefined>): AnalysisPageContext | null {
  const accountId = parsePositiveId(query.accountId);
  const month = query.month;
  if (accountId === null || month === undefined || !isAnalysisMonth(month)) {
    return null;
  }
  return { accountId, month };
}

function parsePositiveId(value: string | undefined): number | null {
  if (value === undefined || !/^[1-9][0-9]*$/.test(value)) {
    return null;
  }
  const id = Number(value);
  return Number.isSafeInteger(id) ? id : null;
}
