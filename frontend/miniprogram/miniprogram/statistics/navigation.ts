import { parseBusinessTimeQueryValue } from "../transaction/navigation";
import { isStatisticsMonth, type StatisticsType } from "./contract";
import type { StatisticsDrill } from "./drilldown";

export interface StatisticsPageContext {
  readonly accountId: number;
  readonly month: string;
  readonly accountCreatedTime: string;
}

export interface StatisticsDetailContext {
  readonly accountId: number;
  readonly month: string;
  readonly type: StatisticsType;
  readonly accountCreatedTime: string;
  readonly drill: StatisticsDrill;
}

export function canOpenStatistics(phase: string, loggingOut: boolean): boolean {
  return !loggingOut && (phase === "ledger-loading" || phase === "ready" || phase === "ledger-error");
}

export function parseStatisticsContext(query: Record<string, string | undefined>): StatisticsPageContext | null {
  const accountId = parsePositiveId(query.accountId);
  const month = query.month;
  const rawCreatedTime = query.accountCreatedTime;
  if (accountId === null || month === undefined || !isStatisticsMonth(month) || rawCreatedTime === undefined) {
    return null;
  }
  const accountCreatedTime = parseBusinessTimeQueryValue(rawCreatedTime);
  if (accountCreatedTime === null) {
    return null;
  }
  return { accountId, month, accountCreatedTime };
}

export function parseStatisticsDetailContext(query: Record<string, string | undefined>): StatisticsDetailContext | null {
  const accountId = parsePositiveId(query.accountId);
  const month = query.month;
  const rawCreatedTime = query.accountCreatedTime;
  const type = query.type;
  if (accountId === null || month === undefined || !isStatisticsMonth(month) || rawCreatedTime === undefined) {
    return null;
  }
  if (type !== "INCOME" && type !== "EXPENSE") {
    return null;
  }
  const accountCreatedTime = parseBusinessTimeQueryValue(rawCreatedTime);
  const drill = parseDrill(query);
  if (accountCreatedTime === null || drill === null) {
    return null;
  }
  return { accountId, month, type, accountCreatedTime, drill };
}

function parseDrill(query: Record<string, string | undefined>): StatisticsDrill | null {
  const title = decodeQueryTitle(query.title);
  if (title === null) {
    return null;
  }
  if (query.drill === "uncategorized") {
    return query.categoryId === undefined && query.tagId === undefined ? { kind: "uncategorized", title } : null;
  }
  if (query.drill === "untagged") {
    return query.tagId === undefined && query.categoryId === undefined ? { kind: "untagged", title } : null;
  }
  if (query.drill === "category") {
    const categoryId = parsePositiveId(query.categoryId);
    return categoryId === null || query.tagId !== undefined ? null : { kind: "category", categoryId, title };
  }
  if (query.drill === "tag") {
    const tagId = parsePositiveId(query.tagId);
    return tagId === null || query.categoryId !== undefined ? null : { kind: "tag", tagId, title };
  }
  return null;
}

function decodeQueryTitle(value: string | undefined): string | null {
  if (value === undefined || value.length === 0) {
    return null;
  }
  try {
    const title = decodeURIComponent(value);
    return title.length === 0 ? null : title;
  } catch {
    return null;
  }
}

function parsePositiveId(value: string | undefined): number | null {
  if (value === undefined || !/^[1-9][0-9]*$/.test(value)) {
    return null;
  }
  const id = Number(value);
  return Number.isSafeInteger(id) ? id : null;
}
