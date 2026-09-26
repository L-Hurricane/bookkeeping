import { formatBusinessTime, formatMoneyLabel } from "../ledger/ledger";
import type {
  CategoryStatisticsItem,
  StatisticsBundle,
  StatisticsType,
  TagStatisticsItem,
  TransactionListItem,
} from "./contract";
import type { StatisticsDrill } from "./drilldown";

export const STATISTICS_LOAD_ERROR = "统计读取失败，请重新加载";
export const STATISTICS_CONTRACT_ERROR = "统计数据不符合约定";
export const STATISTICS_EMPTY = "本月暂无收支记录";
export const STATISTICS_OVERLAP_NOTE = "不同标签可能包含同一笔交易，不能相加作为总收入或总支出。";
export const DETAIL_LOAD_ERROR = "明细读取失败，请重新加载";
export const DETAIL_CONTRACT_ERROR = "明细数据不符合约定";
export const DETAIL_EMPTY = "暂无交易";
export const UNNAMED_TRANSACTION = "未命名交易";

export interface StatisticsScreen {
  readonly generation: number;
  readonly month: string;
  readonly type: StatisticsType;
  readonly phase: "loading" | "ready" | "error";
  readonly bundle: StatisticsBundle | null;
  readonly error: string;
}

export interface StatisticsRow {
  readonly key: string;
  readonly title: string;
  readonly countLabel: string;
  readonly amountLabel: string;
  readonly depth: 0 | 1;
  readonly drill: StatisticsDrill | null;
}

export interface TransactionRow {
  readonly id: number;
  readonly title: string;
  readonly amountLabel: string;
  readonly timeLabel: string;
}

export function openStatistics(month: string, type: StatisticsType): StatisticsScreen {
  return { generation: 0, month, type, phase: "loading", bundle: null, error: "" };
}

export function requestStatistics(screen: StatisticsScreen, month: string, type: StatisticsType): { screen: StatisticsScreen; generation: number } {
  const generation = screen.generation + 1;
  return {
    generation,
    screen: { generation, month, type, phase: "loading", bundle: null, error: "" },
  };
}

export function settleStatistics(screen: StatisticsScreen, generation: number, bundle: StatisticsBundle, pageAlive: boolean): StatisticsScreen {
  if (!pageAlive || screen.generation !== generation) {
    return screen;
  }
  if (!bundleMatches(screen, bundle)) {
    return { ...screen, phase: "error", bundle: null, error: STATISTICS_CONTRACT_ERROR };
  }
  return { ...screen, phase: "ready", bundle, error: "" };
}

export function failStatistics(screen: StatisticsScreen, generation: number, error: string, pageAlive: boolean): StatisticsScreen {
  if (!pageAlive || screen.generation !== generation) {
    return screen;
  }
  return { ...screen, phase: "error", bundle: null, error };
}

export function categoryRows(items: readonly CategoryStatisticsItem[]): StatisticsRow[] {
  const rows: StatisticsRow[] = [];
  for (const item of items) {
    if (item.kind === "UNCATEGORIZED" || item.categoryId === null) {
      rows.push(row("uncategorized", item.categoryName, item.transactionCount, item.amount, 0, { kind: "uncategorized", title: item.categoryName }));
      continue;
    }
    const categoryId = item.categoryId;
    const aggregate = item.children.length > 0;
    rows.push(row(`category-${categoryId}`, item.categoryName, item.transactionCount, item.amount, 0, aggregate ? null : {
      kind: "category",
      categoryId,
      title: item.categoryName,
    }));
    for (const child of item.children) {
      rows.push(row(`category-${child.categoryId}`, child.categoryName, child.transactionCount, child.amount, 1, {
        kind: "category",
        categoryId: child.categoryId,
        title: child.categoryName,
      }));
    }
  }
  return rows;
}

export function tagRows(items: readonly TagStatisticsItem[]): StatisticsRow[] {
  return items.map((item) => {
    if (item.kind === "UNTAGGED" || item.tagId === null) {
      return row("untagged", item.tagName, item.transactionCount, item.amount, 0, { kind: "untagged", title: item.tagName });
    }
    const tagId = item.tagId;
    return row(`tag-${tagId}`, item.tagName, item.transactionCount, item.amount, 0, {
      kind: "tag",
      tagId,
      title: item.tagName,
    });
  });
}

export function transactionRows(items: readonly TransactionListItem[]): TransactionRow[] {
  return items.map((item) => ({
    id: item.id,
    title: item.displayName === null || item.displayName.length === 0 ? UNNAMED_TRANSACTION : item.displayName,
    amountLabel: formatMoneyLabel(item.amount),
    timeLabel: formatBusinessTime(item.businessTime),
  }));
}

export function statisticsIsEmpty(bundle: StatisticsBundle): boolean {
  return bundle.monthly.transactionCount === 0;
}

export interface TransactionDetailScreen {
  readonly generation: number;
  readonly phase: "loading" | "ready" | "error";
  readonly items: readonly TransactionListItem[];
  readonly error: string;
}

export function openTransactionDetail(): TransactionDetailScreen {
  return { generation: 0, phase: "loading", items: [], error: "" };
}

export function requestTransactionDetail(screen: TransactionDetailScreen): { screen: TransactionDetailScreen; generation: number } {
  const generation = screen.generation + 1;
  return { generation, screen: { generation, phase: "loading", items: [], error: "" } };
}

export function settleTransactionDetail(
  screen: TransactionDetailScreen,
  generation: number,
  items: readonly TransactionListItem[],
  pageAlive: boolean,
): TransactionDetailScreen {
  if (!pageAlive || screen.generation !== generation) {
    return screen;
  }
  return { ...screen, phase: "ready", items, error: "" };
}

export function failTransactionDetail(screen: TransactionDetailScreen, generation: number, error: string, pageAlive: boolean): TransactionDetailScreen {
  if (!pageAlive || screen.generation !== generation) {
    return screen;
  }
  return { ...screen, phase: "error", items: [], error };
}

function bundleMatches(screen: StatisticsScreen, bundle: StatisticsBundle): boolean {
  return bundle.monthly.month === screen.month
    && bundle.categories.month === screen.month
    && bundle.categories.type === screen.type
    && bundle.tags.month === screen.month
    && bundle.tags.type === screen.type;
}

function row(key: string, title: string, count: number, amount: string, depth: 0 | 1, drill: StatisticsDrill | null): StatisticsRow {
  return { key, title, countLabel: `${count} 笔`, amountLabel: formatMoneyLabel(amount), depth, drill };
}
