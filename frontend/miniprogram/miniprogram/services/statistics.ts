import type { HttpClient } from "../http/client";
import {
  parseCategoryStatistics,
  parseMonthlyStatistics,
  parseTagStatistics,
  parseTransactionList,
  type CategoryStatistics,
  type MonthlyStatistics,
  type StatisticsBundle,
  type StatisticsType,
  type TagStatistics,
  type TransactionList,
} from "../statistics/contract";
import { transactionListPath, type StatisticsDrill } from "../statistics/drilldown";

export class StatisticsResponseError extends Error {
  constructor(message = "统计响应无法识别") {
    super(message);
    this.name = "StatisticsResponseError";
  }
}

export interface StatisticsApi {
  load(accountId: number, month: string, type: StatisticsType): Promise<StatisticsBundle>;
  listTransactions(accountId: number, month: string, type: StatisticsType, drill: StatisticsDrill, accountCreatedTime: string): Promise<TransactionList>;
}

export function createStatisticsApi(http: HttpClient): StatisticsApi {
  return {
    async load(accountId, month, type) {
      const [monthlyBody, categoryBody, tagBody] = await Promise.all([
        http.request<unknown>({ method: "GET", path: monthlyPath(accountId, month), authenticated: true }),
        http.request<unknown>({ method: "GET", path: categoryPath(accountId, month, type), authenticated: true }),
        http.request<unknown>({ method: "GET", path: tagPath(accountId, month, type), authenticated: true }),
      ]);
      const monthly = parseMonthlyStatistics(monthlyBody, month);
      const categories = parseCategoryStatistics(categoryBody, month, type);
      const tags = parseTagStatistics(tagBody, month, type);
      if (monthly === null || categories === null || tags === null) {
        throw new StatisticsResponseError();
      }
      return { monthly, categories, tags };
    },
    async listTransactions(accountId, month, type, drill, accountCreatedTime) {
      const body = await http.request<unknown>({
        method: "GET",
        path: transactionListPath(accountId, month, type, drill),
        authenticated: true,
      });
      const list = parseTransactionList(body, month, type, accountCreatedTime);
      if (list === null) {
        throw new StatisticsResponseError("明细响应无法识别");
      }
      return list;
    },
  };
}

export function monthlyPath(accountId: number, month: string): string {
  return `/api/v1/accounts/${accountId}/statistics/monthly?month=${month}`;
}

export function categoryPath(accountId: number, month: string, type: StatisticsType): string {
  return `/api/v1/accounts/${accountId}/statistics/categories?month=${month}&type=${type}`;
}

export function tagPath(accountId: number, month: string, type: StatisticsType): string {
  return `/api/v1/accounts/${accountId}/statistics/tags?month=${month}&type=${type}`;
}

export type { CategoryStatistics, MonthlyStatistics, TagStatistics };
