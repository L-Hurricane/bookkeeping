import type { StatisticsType } from "./contract";

export type StatisticsDrill =
  | { readonly kind: "category"; readonly categoryId: number; readonly title: string }
  | { readonly kind: "uncategorized"; readonly title: string }
  | { readonly kind: "tag"; readonly tagId: number; readonly title: string }
  | { readonly kind: "untagged"; readonly title: string };

export function transactionListPath(accountId: number, month: string, type: StatisticsType, drill: StatisticsDrill): string {
  const filter = drillFilter(drill);
  return `/api/v1/accounts/${accountId}/transactions?month=${month}&type=${type}&${filter}`;
}

export function ledgerStatisticsEntryPath(): string {
  return "/pages/statistics/index";
}

export function statisticsPagePath(accountId: number, month: string, accountCreatedTime: string): string {
  return `/pages/statistics/index?accountId=${accountId}&month=${encodeURIComponent(month)}&accountCreatedTime=${encodeURIComponent(accountCreatedTime)}`;
}

export function statisticsDetailPath(accountId: number, month: string, type: StatisticsType, accountCreatedTime: string, drill: StatisticsDrill): string {
  const identity = drillIdentity(drill);
  return `/pages/statistics-detail/index?accountId=${accountId}&month=${encodeURIComponent(month)}&type=${type}&accountCreatedTime=${encodeURIComponent(accountCreatedTime)}&title=${encodeURIComponent(drill.title)}&${identity}`;
}

function drillFilter(drill: StatisticsDrill): string {
  switch (drill.kind) {
    case "category":
      return `categoryId=${drill.categoryId}`;
    case "uncategorized":
      return "uncategorizedOnly=true";
    case "tag":
      return `tagId=${drill.tagId}`;
    case "untagged":
      return "untaggedOnly=true";
  }
}

function drillIdentity(drill: StatisticsDrill): string {
  switch (drill.kind) {
    case "category":
      return `drill=category&categoryId=${drill.categoryId}`;
    case "uncategorized":
      return "drill=uncategorized";
    case "tag":
      return `drill=tag&tagId=${drill.tagId}`;
    case "untagged":
      return "drill=untagged";
  }
}
