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

Page({
  context: null as StatisticsDetailContext | null,
  screen: openTransactionDetail(),
  pageAlive: true,

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

  publish() {
    const rows = transactionRows(this.screen.items);
    this.setData({
      phase: this.screen.phase,
      rows,
      empty: this.screen.phase === "ready" && rows.length === 0,
      errorMessage: this.screen.error,
    });
  },

  onShow() {
    void this.load();
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
    wx.navigateTo({
      url: transactionDetailPath(context.accountId, id, context.accountCreatedTime, context.month),
    });
  },

  async load() {
    const context = this.context;
    if (context === null) {
      return;
    }
    const requested = requestTransactionDetail(this.screen);
    this.screen = requested.screen;
    this.publish();
    try {
      const list = await statisticsApi.listTransactions(context.accountId, context.month, context.type, context.drill, context.accountCreatedTime);
      this.screen = settleTransactionDetail(this.screen, requested.generation, list.items, this.pageAlive);
    } catch (error) {
      if (!this.pageAlive || error instanceof ApiError && error.status === 401) {
        return;
      }
      const message = error instanceof StatisticsResponseError ? DETAIL_CONTRACT_ERROR : DETAIL_LOAD_ERROR;
      this.screen = failTransactionDetail(this.screen, requested.generation, message, this.pageAlive);
    }
    this.publish();
  },
});
