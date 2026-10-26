import { ApiError } from "../../http/client";
import { ledgerStatisticsApi, statisticsApi } from "../../services/runtime";
import { LedgerStatisticsResponseError } from "../../services/ledger-statistics";
import { StatisticsResponseError } from "../../services/statistics";
import { parseLedgerDetailContext, type LedgerDetailContext } from "../../statistics/ledger-contract";
import { transactionViews, type LedgerTransactionView } from "../../statistics/ledger-view";
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
  ledger: null as LedgerDetailContext | null,
  screen: openTransactionDetail(),
  pageAlive: true,
  pageScrollTop: 0,
  detailReturn: null as DetailReturn | null,
  scrollRestoreGeneration: 0,

  data: {
    invalid: false,
    phase: "loading" as TransactionDetailScreen["phase"],
    title: "",
    rows: [] as Array<TransactionRow | LedgerTransactionView>,
    directAvailable: false,
    directLabel: "",
    empty: false,
    emptyMessage: DETAIL_EMPTY,
    errorMessage: "",
  },

  onLoad(query: Record<string, string | undefined>) {
    const ledger = parseLedgerDetailContext(query);
    if (ledger !== null) {
      this.ledger = ledger;
      this.setData({ title: ledger.drill.title, ...directState(ledger) });
      return;
    }
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
    if (this.ledger !== null) {
      const visit = this.detailReturn;
      const plan = detailReturnPlan(visit, ledgerListContext(this.ledger), false);
      this.detailReturn = null;
      if (plan.refresh === "keep") return;
      void this.loadLedger(plan.refresh === "background-refresh" ? "background" : "replace", plan.scrollTop);
      return;
    }
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
    if (this.ledger !== null) {
      void this.loadLedger();
      return;
    }
    void this.load();
  },

  onToggleTagMode() {
    const ledger = this.ledger;
    if (ledger === null || ledger.drill.kind !== "tag") return;
    const tagMode = ledger.drill.tagMode === "TREE" ? "DIRECT" : "TREE";
    this.ledger = { ...ledger, drill: { ...ledger.drill, tagMode } };
    this.setData(directState(this.ledger));
    void this.loadLedger();
  },

  onOpenTransaction(event: WechatMiniprogram.BaseEvent) {
    const ledger = this.ledger;
    const id = Number(event.currentTarget.dataset["id"]);
    if (ledger !== null) {
      const accountId = Number(event.currentTarget.dataset["accountId"]);
      const created = event.currentTarget.dataset["created"];
      const originMonth = event.currentTarget.dataset["month"];
      if (!Number.isSafeInteger(id) || id <= 0 || !Number.isSafeInteger(accountId) || accountId <= 0 || typeof created !== "string" || typeof originMonth !== "string") {
        return;
      }
      this.detailReturn = { dirty: false, context: ledgerListContext(ledger), scrollTop: this.pageScrollTop };
      wx.navigateTo({
        url: transactionDetailPath(accountId, id, created, originMonth),
        events: {
          transactionUpdated: () => { this.markDetailDirty(); },
          listStale: () => { this.markDetailDirty(); },
        },
      });
      return;
    }
    const context = this.context;
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

  async loadLedger(presentation: "replace" | "background" = "replace", restoreScrollTop: number | null = null) {
    const ledger = this.ledger;
    if (ledger === null) return;
    const background = presentation === "background" && this.data.phase === "ready";
    if (!background) this.setData({ phase: "loading", errorMessage: "", rows: [], empty: false });
    const requested = ++this.scrollRestoreGeneration;
    try {
      const list = await ledgerStatisticsApi.listTransactions(ledger);
      if (!this.pageAlive || requested !== this.scrollRestoreGeneration) return;
      const withMonth = list.items.map((item) => {
        const view = transactionViews([item])[0];
        return view === undefined ? null : { ...view, originMonth: item.businessTime.slice(0, 7) };
      }).filter((row) => row !== null);
      this.setData({ phase: "ready", rows: withMonth, empty: withMonth.length === 0, errorMessage: "" });
      if (background && restoreScrollTop !== null) {
        this.pageScrollTop = restoreScrollTop;
        wx.pageScrollTo({ scrollTop: restoreScrollTop, duration: 0 });
      }
    } catch (error) {
      if (!this.pageAlive || requested !== this.scrollRestoreGeneration || error instanceof ApiError && error.status === 401) return;
      if (background) return;
      const message = error instanceof LedgerStatisticsResponseError ? DETAIL_CONTRACT_ERROR : DETAIL_LOAD_ERROR;
      this.setData({ phase: "error", errorMessage: message, rows: [], empty: false });
    }
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

function directState(ledger: LedgerDetailContext): { directAvailable: boolean; directLabel: string } {
  if (ledger.drill.kind !== "tag") return { directAvailable: false, directLabel: "" };
  return {
    directAvailable: true,
    directLabel: ledger.drill.tagMode === "TREE" ? "直接关联本标签" : "查看标签及下级",
  };
}

function ledgerListContext(ledger: LedgerDetailContext): string {
  const drill = ledger.drill;
  const range = `${ledger.query.preset}|${ledger.query.from ?? ""}|${ledger.query.to ?? ""}|${ledger.query.accountScope}`;
  switch (drill.kind) {
    case "category":
      return `${range}|${ledger.type}|category|${drill.categoryId}`;
    case "uncategorized":
      return `${range}|${ledger.type}|uncategorized`;
    case "tag":
      return `${range}|${ledger.type}|tag|${drill.tagId}|${drill.tagMode}`;
    case "tags":
      return `${range}|${ledger.type}|tags|${drill.tagIds.join(",")}`;
    case "untagged":
      return `${range}|${ledger.type}|untagged`;
  }
}

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
