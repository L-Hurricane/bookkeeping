import { ApiError } from "../../http/client";
import { formatMoneyLabel } from "../../ledger/ledger";
import { ledgerStatisticsApi, statisticsApi, tagApi } from "../../services/runtime";
import { LedgerStatisticsResponseError } from "../../services/ledger-statistics";
import { StatisticsResponseError } from "../../services/statistics";
import { parseLedgerDetailContext, statisticsDetailPagePath, type LedgerDetailContext, type LedgerFlow, type TagAnalysis } from "../../statistics/ledger-contract";
import { flowRows, ringSegments, transactionViews, type LedgerAmountRow, type LedgerTransactionView } from "../../statistics/ledger-view";
import { parseStatisticsDetailContext, type StatisticsDetailContext } from "../../statistics/navigation";
import { tagPickerRows, type TagPickerRow } from "../../transaction/tag-picker";
import type { TagNode } from "../../tag/tag";
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
  tagTree: null as TagNode[] | null,
  andDraft: [] as number[],

  data: {
    invalid: false,
    phase: "loading" as TransactionDetailScreen["phase"],
    title: "",
    rows: [] as Array<TransactionRow | LedgerTransactionView>,
    scopeControl: false,
    tagMode: "" as "" | "TREE" | "DIRECT",
    modeLocked: false,
    modeHint: "",
    andAvailable: false,
    scopeText: "",
    amountLabel: "",
    countLabel: "",
    coverageLabel: "",
    compositionNote: "",
    categories: [] as LedgerAmountRow[],
    children: [] as Array<{ id: number; name: string }>,
    andChips: [] as Array<{ id: number; name: string }>,
    andSheetOpen: false,
    andQuery: "",
    andRows: [] as TagPickerRow[],
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

  onSelectTagMode(event: WechatMiniprogram.BaseEvent) {
    const ledger = this.ledger;
    const mode = event.currentTarget.dataset["mode"];
    if (ledger === null || ledger.drill.kind !== "tag" || (mode !== "TREE" && mode !== "DIRECT")) return;
    if (mode === "DIRECT" && (ledger.drill.tagAnd?.length ?? 0) > 0) {
      this.setData({ modeHint: "同时包含只按标签及下级计算" });
      return;
    }
    if (ledger.drill.tagMode === mode) return;
    this.ledger = { ...ledger, drill: { ...ledger.drill, tagMode: mode, tagAnd: [] } };
    this.setData(directState(this.ledger));
    void this.loadLedger();
  },

  onOpenChild(event: WechatMiniprogram.BaseEvent) {
    const ledger = this.ledger;
    const id = Number(event.currentTarget.dataset["id"]);
    const title = event.currentTarget.dataset["title"];
    if (ledger === null || ledger.drill.kind !== "tag" || !Number.isSafeInteger(id) || id <= 0 || typeof title !== "string" || title.length === 0) return;
    wx.navigateTo({
      url: statisticsDetailPagePath({
        query: ledger.query,
        type: ledger.type,
        drill: { kind: "tag", tagId: id, title, tagMode: "TREE" },
      }),
    });
  },

  onOpenAnd() {
    const ledger = this.ledger;
    if (ledger === null || ledger.drill.kind !== "tag" || ledger.drill.tagMode !== "TREE") return;
    this.andDraft = [...(ledger.drill.tagAnd ?? [])];
    this.setData({ andSheetOpen: true, andQuery: "", andRows: this.andRows("") });
    if (this.tagTree === null) void this.loadTagTree();
  },

  onAndQuery(event: WechatMiniprogram.Input) {
    const andQuery = event.detail.value;
    this.setData({ andQuery, andRows: this.andRows(andQuery) });
  },

  onToggleAnd(event: WechatMiniprogram.BaseEvent) {
    const id = Number(event.currentTarget.dataset["id"]);
    const ledger = this.ledger;
    if (ledger === null || ledger.drill.kind !== "tag" || !Number.isSafeInteger(id) || id <= 0 || id === ledger.drill.tagId) return;
    this.andDraft = this.andDraft.includes(id) ? this.andDraft.filter((item) => item !== id) : [...this.andDraft, id];
    this.setData({ andRows: this.andRows(this.data.andQuery) });
  },

  onCancelAnd() {
    this.andDraft = [];
    this.setData({ andSheetOpen: false });
  },

  onConfirmAnd() {
    const ledger = this.ledger;
    if (ledger === null || ledger.drill.kind !== "tag") return;
    this.ledger = { ...ledger, drill: { ...ledger.drill, tagMode: "TREE", tagAnd: [...this.andDraft] } };
    this.andDraft = [];
    this.setData({ andSheetOpen: false, ...directState(this.ledger) });
    void this.loadLedger();
  },

  onRemoveAnd(event: WechatMiniprogram.BaseEvent) {
    const ledger = this.ledger;
    const id = Number(event.currentTarget.dataset["id"]);
    if (ledger === null || ledger.drill.kind !== "tag" || !Number.isSafeInteger(id) || id <= 0) return;
    this.ledger = {
      ...ledger,
      drill: { ...ledger.drill, tagAnd: (ledger.drill.tagAnd ?? []).filter((item) => item !== id) },
    };
    this.setData(directState(this.ledger));
    void this.loadLedger();
  },

  onHold() {
    return;
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
      if (ledger.drill.kind === "tag") {
        await this.publishTagAnalysis(ledger, requested, background, restoreScrollTop);
        return;
      }
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

  async publishTagAnalysis(ledger: LedgerDetailContext, requested: number, background: boolean, restoreScrollTop: number | null) {
    if (ledger.drill.kind !== "tag") return;
    const analysis = await ledgerStatisticsApi.loadTagAnalysis(ledger);
    if (!this.pageAlive || requested !== this.scrollRestoreGeneration) return;
    if (this.tagTree === null) void this.loadTagTree();
    const withMonth = analysis.items.map((item) => {
      const view = transactionViews([item])[0];
      return view === undefined ? null : { ...view, originMonth: item.businessTime.slice(0, 7) };
    }).filter((row) => row !== null);
    const flow: LedgerFlow = {
      net: analysis.amount,
      categories: analysis.categories,
      tags: [],
      uncategorized: analysis.uncategorized,
      untagged: null,
    };
    const categories = flowRows(flow, "category").map((row) => ({ ...row, amountToneClass: "money-neutral" }));
    const pie = ringSegments(flow);
    const hasComposition = categories.length > 0;
    const andCount = ledger.drill.tagAnd?.length ?? 0;
    this.setData({
      phase: "ready",
      rows: withMonth,
      empty: withMonth.length === 0,
      emptyMessage: andCount > 0 && withMonth.length === 0 ? "没有同时包含这些标签的交易。" : DETAIL_EMPTY,
      errorMessage: "",
      ...directState(ledger),
      ...analysisView(ledger, analysis, categories, pie === null && hasComposition),
      children: this.tagTree === null || ledger.drill.kind !== "tag" ? [] : childEntries(this.tagTree, ledger.drill.tagId),
      andChips: andChips(this.tagTree ?? [], ledger),
    });
    if (background && restoreScrollTop !== null) {
      this.pageScrollTop = restoreScrollTop;
      wx.pageScrollTo({ scrollTop: restoreScrollTop, duration: 0 });
    }
  },

  async loadTagTree() {
    const ledger = this.ledger;
    if (ledger === null || ledger.drill.kind !== "tag") return;
    try {
      const tree = await tagApi.listTagTree(ledger.type);
      const current = this.ledger;
      if (!this.pageAlive || current === null || current.drill.kind !== "tag") return;
      this.tagTree = tree;
      this.setData({
        children: childEntries(tree, current.drill.tagId),
        andChips: andChips(tree, current),
        andRows: this.data.andSheetOpen ? this.andRows(this.data.andQuery) : [],
      });
    } catch (error) {
      if (!this.pageAlive || error instanceof ApiError && error.status === 401) return;
    }
  },

  andRows(query: string): TagPickerRow[] {
    const ledger = this.ledger;
    if (ledger === null || ledger.drill.kind !== "tag" || this.tagTree === null) return [];
    const subjectId = ledger.drill.tagId;
    return tagPickerRows(this.tagTree, this.andDraft, query).filter((row) => row.id !== subjectId);
  },
});

function directState(ledger: LedgerDetailContext): {
  scopeControl: boolean;
  tagMode: "" | "TREE" | "DIRECT";
  modeLocked: boolean;
  modeHint: string;
  andAvailable: boolean;
} {
  if (ledger.drill.kind !== "tag") {
    return { scopeControl: false, tagMode: "", modeLocked: false, modeHint: "", andAvailable: false };
  }
  const locked = (ledger.drill.tagAnd?.length ?? 0) > 0;
  return {
    scopeControl: true,
    tagMode: ledger.drill.tagMode,
    modeLocked: locked,
    modeHint: locked ? "同时包含只按标签及下级计算" : "",
    andAvailable: ledger.drill.tagMode === "TREE",
  };
}

function childEntries(nodes: readonly TagNode[], id: number): Array<{ id: number; name: string }> {
  const found = findTag(nodes, id);
  return found === null ? [] : found.children.map((child) => ({ id: child.id, name: child.name }));
}

function findTag(nodes: readonly TagNode[], id: number): TagNode | null {
  for (const node of nodes) {
    if (node.id === id) return node;
    const nested = findTag(node.children, id);
    if (nested !== null) return nested;
  }
  return null;
}

function tagName(nodes: readonly TagNode[], id: number): string {
  return findTag(nodes, id)?.name ?? "标签";
}

function andChips(nodes: readonly TagNode[], ledger: LedgerDetailContext): Array<{ id: number; name: string }> {
  if (ledger.drill.kind !== "tag") return [];
  return (ledger.drill.tagAnd ?? []).map((id) => ({ id, name: tagName(nodes, id) }));
}

function analysisView(ledger: LedgerDetailContext, analysis: TagAnalysis, categories: LedgerAmountRow[], degraded: boolean): {
  scopeText: string;
  amountLabel: string;
  countLabel: string;
  coverageLabel: string;
  compositionNote: string;
  categories: LedgerAmountRow[];
} {
  const kind = analysis.scope === "AND" ? "同时包含所选标签及其下级 · 同一交易仅计一次"
    : analysis.scope === "DIRECT" ? "仅直接关联本标签，不含只挂在下级的交易"
      : "含下级标签 · 同一交易仅计一次";
  const flowName = ledger.type === "INCOME" ? "收入" : "支出";
  const composition = "占比基于当前标签分析结果；父分类包含子分类，不能逐行相加。";
  return {
    scopeText: kind,
    amountLabel: formatMoneyLabel(analysis.amount),
    countLabel: `${analysis.transactionCount} 笔`,
    coverageLabel: analysis.coverage === null
      ? "当前范围净额为零，不计算覆盖率。"
      : `占当前范围全部${flowName} ${analysis.coverage}%`,
    compositionNote: degraded ? `净额含负数或为零，不按 100% 构成展示。${composition}` : composition,
    categories,
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
      return `${range}|${ledger.type}|tag|${drill.tagId}|${drill.tagMode}|${(drill.tagAnd ?? []).join(",")}`;
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
