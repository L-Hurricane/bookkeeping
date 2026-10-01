import { ApiError } from "../../http/client";
import { statisticsApi } from "../../services/runtime";
import { StatisticsResponseError } from "../../services/statistics";
import { parseStatisticsDetailContext, type StatisticsDetailContext } from "../../statistics/navigation";
import {
  DETAIL_CONTRACT_ERROR,
  DETAIL_EMPTY,
  DETAIL_LOAD_ERROR,
  failTransactionDetail,
  openTransactionDetail,
  requestTransactionDetail,
  settleTransactionDetail,
  transactionRows,
  type TransactionDetailScreen,
  type TransactionRow,
} from "../../statistics/screen";
import { transactionDetailPath } from "../../transaction/navigation";
import { detailReturnPlan, type DetailReturn } from "../../navigation/scroll-restore";

Page({
  context: null as StatisticsDetailContext | null,
  screen: openTransactionDetail(),
  pageAlive: true,
  pageScrollTop: 0,
  detailReturn: null as DetailReturn | null,
  scrollRestoreGeneration: 0,

  data: {
    invalid: false,
    phase: "loading" as TransactionDetailScreen["phase"],
    title: "",
    rows: [] as TransactionRow[],
    empty: false,
    emptyMessage: DETAIL_EMPTY,
    errorMessage: "",
  },

  onLoad(query: Record<string, string | undefined>) {
    const context = parseStatisticsDetailContext(query);
    if (context === null) {
      this.setData({ invalid: true, phase: "error", errorMessage: "页面参数不正确" });
      return;
    }
    this.context = context;
    this.setData({ title: context.drill.title });
  },

  publish(restore: { readonly generation: number; readonly scrollTop: number } | null) {
    const rows = transactionRows(this.screen.items);
    const view = {
      phase: this.screen.phase,
      rows,
      empty: this.screen.phase === "ready" && rows.length === 0,
      errorMessage: this.screen.error,
    };
    if (restore === null) {
      this.setData(view);
      return;
    }
    this.setData(view, () => {
      if (!this.pageAlive || restore.generation !== this.scrollRestoreGeneration) {
        return;
      }
      this.pageScrollTop = restore.scrollTop;
      wx.pageScrollTo({ scrollTop: restore.scrollTop, duration: 0 });
    });
  },

  onShow() {
    const context = this.context;
    const visit = this.detailReturn;
    const plan = detailReturnPlan(visit, context === null ? "" : detailListContext(context), false);
    this.detailReturn = null;
    if (plan.refresh === "keep") {
      return;
    }
    void this.load(plan.refresh === "background-refresh" ? "background" : "replace", plan.scrollTop);
  },

  markDetailDirty() {
    if (this.detailReturn === null) {
      return;
    }
    this.detailReturn = { ...this.detailReturn, dirty: true };
  },

  onPageScroll(event: WechatMiniprogram.Page.IPageScrollOption) {
    if (Number.isFinite(event.scrollTop) && event.scrollTop >= 0) {
      this.pageScrollTop = event.scrollTop;
    }
  },

  onUnload() {
    this.pageAlive = false;
  },

  onRetry() {
    void this.load();
  },

  onOpenTransaction(event: WechatMiniprogram.BaseEvent) {
    const context = this.context;
    const id = Number(event.currentTarget.dataset["id"]);
    if (context === null || !Number.isSafeInteger(id) || id <= 0) {
      return;
    }
    this.detailReturn = { dirty: false, context: detailListContext(context), scrollTop: this.pageScrollTop };
    wx.navigateTo({
      url: transactionDetailPath(context.accountId, id, context.accountCreatedTime, context.month),
      events: {
        transactionUpdated: () => {
          this.markDetailDirty();
        },
        listStale: () => {
          this.markDetailDirty();
        },
      },
    });
  },

  async load(presentation: "replace" | "background" = "replace", restoreScrollTop: number | null = null) {
    const context = this.context;
    if (context === null) {
      return;
    }
    const background = presentation === "background" && this.screen.phase === "ready";
    const scrollRestoreGeneration = ++this.scrollRestoreGeneration;
    let requestedGeneration: number;
    if (background) {
      requestedGeneration = this.screen.generation + 1;
      this.screen = { ...this.screen, generation: requestedGeneration };
    } else {
      const requested = requestTransactionDetail(this.screen);
      this.screen = requested.screen;
      requestedGeneration = requested.generation;
      this.publish(null);
    }
    try {
      const list = await statisticsApi.listTransactions(context.accountId, context.month, context.type, context.drill, context.accountCreatedTime);
      const next = settleTransactionDetail(this.screen, requestedGeneration, list.items, this.pageAlive);
      if (next === this.screen) {
        return;
      }
      this.screen = next;
    } catch (error) {
      if (!this.pageAlive || error instanceof ApiError && error.status === 401) {
        return;
      }
      if (background) {
        return;
      }
      const message = error instanceof StatisticsResponseError ? DETAIL_CONTRACT_ERROR : DETAIL_LOAD_ERROR;
      this.screen = failTransactionDetail(this.screen, requestedGeneration, message, this.pageAlive);
    }
    if (!this.pageAlive) {
      return;
    }
    const restore = background && this.screen.phase === "ready" && restoreScrollTop !== null
      ? { generation: scrollRestoreGeneration, scrollTop: restoreScrollTop }
      : null;
    this.publish(restore);
  },
});

function detailListContext(context: StatisticsDetailContext): string {
  const drill = context.drill;
  switch (drill.kind) {
    case "category":
      return `${context.month}|${context.type}|category|${drill.categoryId}`;
    case "uncategorized":
      return `${context.month}|${context.type}|uncategorized`;
    case "tag":
      return `${context.month}|${context.type}|tag|${drill.tagId}`;
    case "untagged":
      return `${context.month}|${context.type}|untagged`;
  }
}
