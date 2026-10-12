import { formatMoneyLabel } from "../../ledger/ledger";
import { formatMonthLabel, nextMonth, previousMonth } from "../../ledger/month";
import { ApiError } from "../../http/client";
import { statisticsApi } from "../../services/runtime";
import { StatisticsResponseError } from "../../services/statistics";
import { monthlyAnalysisPagePath } from "../../analysis/navigation";
import { statisticsDetailPath } from "../../statistics/drilldown";
import { parseStatisticsContext, type StatisticsPageContext } from "../../statistics/navigation";
import {
  STATISTICS_CONTRACT_ERROR,
  STATISTICS_EMPTY,
  STATISTICS_LOAD_ERROR,
  STATISTICS_OVERLAP_NOTE,
  categoryRows,
  failStatistics,
  openStatistics,
  requestStatistics,
  settleStatistics,
  statisticsIsEmpty,
  tagRows,
  type StatisticsRow,
  type StatisticsScreen,
} from "../../statistics/screen";
import type { StatisticsType } from "../../statistics/contract";
import type { StatisticsDrill } from "../../statistics/drilldown";

Page({
  context: null as StatisticsPageContext | null,
  screen: openStatistics("2000-01", "EXPENSE"),
  pageAlive: true,

  data: {
    invalid: false,
    phase: "loading" as StatisticsScreen["phase"],
    monthLabel: "",
    type: "EXPENSE" as StatisticsType,
    incomeLabel: "",
    expenseLabel: "",
    surplusLabel: "",
    countLabel: "",
    empty: false,
    emptyMessage: STATISTICS_EMPTY,
    categories: [] as StatisticsViewRow[],
    tags: [] as StatisticsViewRow[],
    categoryEmpty: false,
    tagEmpty: false,
    overlapNote: "",
    errorMessage: "",
  },

  onLoad(query: Record<string, string | undefined>) {
    const context = parseStatisticsContext(query);
    if (context === null) {
      this.setData({ invalid: true, phase: "error", errorMessage: "页面参数不正确" });
      return;
    }
    this.context = context;
    this.screen = openStatistics(context.month, "EXPENSE");
  },

  onShow() {
    if (this.context === null) {
      return;
    }
    void this.load(this.screen.month, this.screen.type);
  },

  onUnload() {
    this.pageAlive = false;
  },

  onPreviousMonth() {
    if (this.context === null) {
      return;
    }
    void this.load(previousMonth(this.screen.month), this.screen.type);
  },

  onNextMonth() {
    if (this.context === null) {
      return;
    }
    void this.load(nextMonth(this.screen.month), this.screen.type);
  },

  onSelectType(event: WechatMiniprogram.BaseEvent) {
    const type = event.currentTarget.dataset["type"];
    if (this.context === null || (type !== "INCOME" && type !== "EXPENSE") || type === this.screen.type) {
      return;
    }
    void this.load(this.screen.month, type);
  },

  onRetry() {
    if (this.context === null) {
      return;
    }
    void this.load(this.screen.month, this.screen.type);
  },

  onOpenAnalysis() {
    const context = this.context;
    if (context === null) {
      return;
    }
    wx.navigateTo({ url: monthlyAnalysisPagePath(context.accountId, this.screen.month) });
  },

  onOpenRow(event: WechatMiniprogram.BaseEvent) {
    const context = this.context;
    const drill = drillFromDataset(event.currentTarget.dataset);
    if (context === null || drill === null || this.screen.phase !== "ready") {
      return;
    }
    wx.navigateTo({
      url: statisticsDetailPath(context.accountId, this.screen.month, this.screen.type, context.accountCreatedTime, drill),
    });
  },

  async load(month: string, type: StatisticsType) {
    const context = this.context;
    if (context === null) {
      return;
    }
    const requested = requestStatistics(this.screen, month, type);
    this.screen = requested.screen;
    this.publish();
    try {
      const bundle = await statisticsApi.load(context.accountId, month, type);
      this.screen = settleStatistics(this.screen, requested.generation, bundle, this.pageAlive);
    } catch (error) {
      if (!this.pageAlive || error instanceof ApiError && error.status === 401) {
        return;
      }
      const message = error instanceof StatisticsResponseError ? STATISTICS_CONTRACT_ERROR : STATISTICS_LOAD_ERROR;
      this.screen = failStatistics(this.screen, requested.generation, message, this.pageAlive);
    }
    this.publish();
  },

  publish() {
    const bundle = this.screen.bundle;
    const empty = bundle !== null && statisticsIsEmpty(bundle);
    const categories = bundle === null || empty ? [] : categoryRows(bundle.categories.items).map(viewRow);
    const tags = bundle === null || empty ? [] : tagRows(bundle.tags.items).map(viewRow);
    this.setData({
      invalid: false,
      phase: this.screen.phase,
      monthLabel: formatMonthLabel(this.screen.month),
      type: this.screen.type,
      incomeLabel: bundle === null ? "" : formatMoneyLabel(bundle.monthly.netIncome),
      expenseLabel: bundle === null ? "" : formatMoneyLabel(bundle.monthly.netExpense),
      surplusLabel: bundle === null ? "" : formatMoneyLabel(bundle.monthly.surplus),
      countLabel: bundle === null ? "" : `${bundle.monthly.transactionCount} 笔`,
      empty,
      categories,
      tags,
      categoryEmpty: bundle !== null && !empty && categories.length === 0,
      tagEmpty: bundle !== null && !empty && tags.length === 0,
      overlapNote: bundle !== null && !empty ? STATISTICS_OVERLAP_NOTE : "",
      errorMessage: this.screen.error,
    });
  },
});

interface StatisticsViewRow {
  readonly key: string;
  readonly title: string;
  readonly countLabel: string;
  readonly amountLabel: string;
  readonly depth: 0 | 1;
  readonly drillKind: StatisticsDrill["kind"] | "";
  readonly drillId: number;
}

function viewRow(row: StatisticsRow): StatisticsViewRow {
  const drill = row.drill;
  return {
    key: row.key,
    title: row.title,
    countLabel: row.countLabel,
    amountLabel: row.amountLabel,
    depth: row.depth,
    drillKind: drill === null ? "" : drill.kind,
    drillId: drill?.kind === "category" ? drill.categoryId : drill?.kind === "tag" ? drill.tagId : 0,
  };
}

function drillFromDataset(dataset: Record<string, unknown>): StatisticsDrill | null {
  const title = dataset["title"];
  const kind = dataset["drill"];
  if (typeof title !== "string" || title.length === 0) {
    return null;
  }
  if (kind === "uncategorized") {
    return { kind: "uncategorized", title };
  }
  if (kind === "untagged") {
    return { kind: "untagged", title };
  }
  const id = Number(dataset["id"]);
  if (!Number.isSafeInteger(id) || id <= 0) {
    return null;
  }
  if (kind === "category") {
    return { kind: "category", categoryId: id, title };
  }
  if (kind === "tag") {
    return { kind: "tag", tagId: id, title };
  }
  return null;
}
