import { moneyToneClass } from "../../account/money-tone";
import { ApiError } from "../../http/client";
import { formatMoneyLabel } from "../../ledger/ledger";
import { currentBeijingMonth } from "../../ledger/month";
import { ledgerAnalysisPagePath, monthlyAnalysisPagePath } from "../../analysis/navigation";
import { accountApi, ledgerStatisticsApi } from "../../services/runtime";
import { LedgerStatisticsResponseError } from "../../services/ledger-statistics";
import {
  statisticsDetailPagePath,
  type LedgerAccountScope,
  type LedgerDrill,
  type LedgerFlowType,
  type LedgerPreset,
  type LedgerQuery,
  type LedgerReport,
} from "../../statistics/ledger-contract";
import { flowRows, pointHeights, ringSegments, type LedgerAmountRow, type RingSegment } from "../../statistics/ledger-view";
import { allTransactionsPath } from "../../ledger/feed-contract";
import { takeStatisticsAccount } from "../../statistics/tab-context";

const LOAD_ERROR = "统计暂时没有读取成功";
const CONTRACT_ERROR = "统计响应无法识别";

type ModuleName = "NET" | LedgerFlowType;
type BreakdownName = "CATEGORY" | "TAG";

interface PickerAccount {
  readonly id: number;
  readonly name: string;
  readonly archived: boolean;
  readonly checked: boolean;
}

Page({
  preset: "MONTH" as LedgerPreset,
  scope: "ACTIVE" as LedgerAccountScope,
  checkedIds: [] as number[],
  customFrom: "",
  customTo: "",
  generation: 0,
  pageAlive: true,
  aiAccountId: null as number | null,
  report: null as LedgerReport | null,
  module: "NET" as ModuleName,
  breakdown: "CATEGORY" as BreakdownName,
  picker: [] as PickerAccount[],
  selectedTagIds: [] as number[],
  ringSegments: [] as RingSegment[],

  data: {
    phase: "loading" as "loading" | "ready" | "error" | "choose",
    errorMessage: "",
    preset: "MONTH" as LedgerPreset,
    scope: "ACTIVE" as LedgerAccountScope,
    module: "NET" as ModuleName,
    breakdown: "CATEGORY" as BreakdownName,
    rangeLabel: "",
    picker: [] as PickerAccount[],
    openingLabel: "",
    closingLabel: "",
    changeLabel: "",
    openingTone: "",
    closingTone: "",
    changeTone: "",
    points: [] as Array<{ date: string; amountLabel: string; tone: string; height: number }>,
    netLabel: "",
    netTone: "",
    categories: [] as LedgerAmountRow[],
    tags: [] as LedgerAmountRow[],
    emptyFlow: false,
    selectedTagCount: 0,
    showAnalysis: false,
    customFrom: "",
    customTo: "",
    moreOpen: false,
    ringNote: "",
    ringSegments: [] as RingSegment[],
  },

  onLoad(query: Record<string, string | undefined>) {
    const accountId = Number(query.accountId);
    this.aiAccountId = Number.isSafeInteger(accountId) && accountId > 0 ? accountId : null;
    void this.prepare();
  },

  onShow() {
    const accountId = takeStatisticsAccount();
    if (accountId === undefined) return;
    this.aiAccountId = accountId;
    if (this.report !== null) this.publish();
    else this.setData({ showAnalysis: accountId !== null });
  },

  onTabItemTap() {
    this.aiAccountId = null;
    if (this.report !== null) this.publish();
    else this.setData({ showAnalysis: false });
  },

  onUnload() {
    this.pageAlive = false;
  },

  async prepare() {
    try {
      const [active, archived] = await Promise.all([
        accountApi.listAccounts("ACTIVE"),
        accountApi.listAccounts("ARCHIVED"),
      ]);
      if (!this.pageAlive) return;
      this.picker = [
        ...active.map((account) => ({ id: account.id, name: account.name, archived: false, checked: false })),
        ...archived.map((account) => ({ id: account.id, name: account.name, archived: true, checked: false })),
      ];
    } catch (error) {
      if (!this.pageAlive || error instanceof ApiError && error.status === 401) return;
      this.picker = [];
    }
    await this.load();
  },

  onSelectPreset(event: WechatMiniprogram.BaseEvent) {
    const preset = event.currentTarget.dataset["preset"];
    if (preset !== "MONTH" && preset !== "QUARTER" && preset !== "YEAR"
      && preset !== "ROLLING_MONTH" && preset !== "ROLLING_3_MONTHS" && preset !== "ROLLING_YEAR" && preset !== "CUSTOM") return;
    if (preset === "CUSTOM" && (this.customFrom.length === 0 || this.customTo.length === 0)) {
      if (this.report === null) return;
      this.customFrom = this.report.from;
      this.customTo = this.report.to;
    }
    this.preset = preset;
    void this.load();
  },

  onToggleMore() {
    this.setData({ moreOpen: !this.data.moreOpen });
  },

  onOpenPeriod() {
    const report = this.report;
    if (report === null) return;
    const accountIds = this.scope === "SELECTED" ? this.checkedIds.join(",") : "";
    wx.navigateTo({
      url: allTransactionsPath({
        from: report.from,
        to: report.to,
        accountScope: this.scope,
        accountIds,
      }),
    });
  },

  onCustomFrom(event: WechatMiniprogram.PickerChange) {
    this.customFrom = String(event.detail.value);
    this.preset = "CUSTOM";
    void this.load();
  },

  onCustomTo(event: WechatMiniprogram.PickerChange) {
    this.customTo = String(event.detail.value);
    this.preset = "CUSTOM";
    void this.load();
  },

  onSelectScope(event: WechatMiniprogram.BaseEvent) {
    const scope = event.currentTarget.dataset["scope"];
    if (scope !== "ACTIVE" && scope !== "ALL" && scope !== "SELECTED") return;
    this.scope = scope;
    void this.load();
  },

  onToggleAccount(event: WechatMiniprogram.BaseEvent) {
    const id = Number(event.currentTarget.dataset["id"]);
    if (!Number.isSafeInteger(id)) return;
    this.checkedIds = this.checkedIds.includes(id) ? this.checkedIds.filter((item) => item !== id) : [...this.checkedIds, id];
    this.picker = this.picker.map((account) => ({ ...account, checked: this.checkedIds.includes(account.id) }));
    void this.load();
  },

  onSelectModule(event: WechatMiniprogram.BaseEvent) {
    const moduleName = event.currentTarget.dataset["module"];
    if (moduleName !== "NET" && moduleName !== "EXPENSE" && moduleName !== "INCOME") return;
    this.module = moduleName;
    this.publish();
  },

  onSelectBreakdown(event: WechatMiniprogram.BaseEvent) {
    const breakdown = event.currentTarget.dataset["breakdown"];
    if (breakdown !== "CATEGORY" && breakdown !== "TAG") return;
    this.breakdown = breakdown;
    this.publish();
  },

  onRetry() {
    void this.load();
  },

  onOpenAnalysis() {
    const accountId = this.aiAccountId;
    if (accountId === null || this.report === null) return;
    wx.navigateTo({ url: monthlyAnalysisPagePath(accountId, this.report.from.slice(0, 7)) });
  },

  onOpenLedgerAnalysis() {
    wx.navigateTo({ url: ledgerAnalysisPagePath("MONTH", currentBeijingMonth(new Date())) });
  },

  onToggleTag(event: WechatMiniprogram.BaseEvent) {
    const id = Number(event.currentTarget.dataset["id"]);
    if (!Number.isSafeInteger(id) || id <= 0) return;
    this.selectedTagIds = this.selectedTagIds.includes(id)
      ? this.selectedTagIds.filter((item) => item !== id)
      : [...this.selectedTagIds, id];
    this.publish();
  },

  onOpenSelectedTags() {
    const query = this.currentQuery();
    if (query === null || this.selectedTagIds.length === 0 || (this.module !== "INCOME" && this.module !== "EXPENSE")) return;
    wx.navigateTo({
      url: statisticsDetailPagePath({
        query,
        type: this.module,
        drill: { kind: "tags", tagIds: this.selectedTagIds, title: "所选标签" },
      }),
    });
  },

  onOpenRow(event: WechatMiniprogram.BaseEvent) {
    const report = this.report;
    const query = this.currentQuery();
    const drill = drillFromDataset(event.currentTarget.dataset);
    if (report === null || query === null || drill === null || (this.module !== "INCOME" && this.module !== "EXPENSE")) return;
    wx.navigateTo({
      url: statisticsDetailPagePath({ query, type: this.module, drill }),
    });
  },

  async load() {
    const query = this.currentQuery();
    const requested = ++this.generation;
    if (query === null) {
      this.report = null;
      const message = this.scope === "SELECTED" && this.checkedIds.length === 0 ? "请选择账户" : "请选择日期";
      this.setData({ ...this.chrome(), phase: "choose", errorMessage: message, rangeLabel: "" });
      return;
    }
    this.setData({ ...this.chrome(), phase: "loading", errorMessage: "" });
    try {
      const report = await ledgerStatisticsApi.load(query);
      if (!this.pageAlive || requested !== this.generation) return;
      this.report = report;
      if (this.customFrom.length === 0) {
        this.customFrom = report.from;
        this.customTo = report.to;
      }
      this.publish();
    } catch (error) {
      if (!this.pageAlive || requested !== this.generation || error instanceof ApiError && error.status === 401) return;
      this.report = null;
      const message = error instanceof LedgerStatisticsResponseError ? CONTRACT_ERROR : LOAD_ERROR;
      this.setData({ ...this.chrome(), phase: "error", errorMessage: message });
    }
  },

  publish() {
    const report = this.report;
    if (report === null) return;
    const net = report.netWorth;
    const flow = this.module === "INCOME" ? report.income : report.expense;
    const categories = this.module === "NET" ? [] : flowRows(flow, "category");
    const ring = this.module !== "NET" && this.breakdown === "CATEGORY" ? ringSegments(flow) : null;
    this.ringSegments = ring ?? [];
    const heights = pointHeights(net.points.map((point) => point.amount));
    const tags = this.module === "NET" ? [] : flowRows(flow, "tag").map((row) => ({
      ...row,
      selected: row.drillKind === "tag" && this.selectedTagIds.includes(row.drillId),
    }));
    this.setData({
      ...this.chrome(),
      phase: "ready",
      errorMessage: "",
      rangeLabel: `${report.from} 至 ${report.to}`,
      openingLabel: formatMoneyLabel(net.opening),
      closingLabel: formatMoneyLabel(net.closing),
      changeLabel: formatMoneyLabel(net.change),
      openingTone: moneyToneClass(net.opening),
      closingTone: moneyToneClass(net.closing),
      changeTone: moneyToneClass(net.change),
      points: net.points.map((point, index) => ({
        date: point.date,
        amountLabel: formatMoneyLabel(point.amount),
        tone: moneyToneClass(point.amount),
        height: heights[index] ?? 24,
      })),
      ringNote: ring === null && this.module !== "NET" && this.breakdown === "CATEGORY"
        ? "净额含负数或为零，环形图不按 100% 构成展示。"
        : "",
      ringSegments: this.ringSegments,
      netLabel: this.module === "NET" ? "" : formatMoneyLabel(flow.net),
      netTone: this.module === "NET" ? "" : moneyToneClass(flow.net),
      categories,
      tags,
      emptyFlow: this.module !== "NET" && flow.net === "0.00" && (this.breakdown === "TAG" ? tags.length === 0 : categories.length === 0),
      selectedTagCount: this.selectedTagIds.length,
      showAnalysis: this.aiAccountId !== null,
      customFrom: this.customFrom,
      customTo: this.customTo,
    }, () => this.drawRing());
  },

  drawRing() {
    const segments = this.ringSegments;
    if (segments.length === 0) return;
    wx.createSelectorQuery().in(this).select("#category-ring").fields({ node: true, size: true }).exec((result) => {
      const node = result?.[0] as { node?: WechatMiniprogram.Canvas; width?: number; height?: number } | undefined;
      if (node?.node === undefined || node.width === undefined || node.height === undefined) return;
      const canvas = node.node;
      const ctx = canvas.getContext("2d");
      const dpr = wx.getWindowInfo().pixelRatio || 1;
      canvas.width = node.width * dpr;
      canvas.height = node.height * dpr;
      ctx.scale(dpr, dpr);
      const colors = ["#c23a2b", "#1f7a4c", "#3d5a80", "#c47b2b", "#6b5b95", "#8a8a8a"];
      let start = -Math.PI / 2;
      const cx = node.width / 2;
      const cy = node.height / 2;
      const radius = Math.min(node.width, node.height) / 2 - 8;
      for (let index = 0; index < segments.length; index += 1) {
        const segment = segments[index];
        if (segment === undefined) continue;
        const weight = Number(segment.share);
        if (!Number.isFinite(weight) || weight <= 0) continue;
        const sweep = (weight / 100) * Math.PI * 2;
        ctx.beginPath();
        ctx.moveTo(cx, cy);
        ctx.arc(cx, cy, radius, start, start + sweep);
        ctx.closePath();
        ctx.fillStyle = colors[index % colors.length] ?? "#8a8a8a";
        ctx.fill();
        start += sweep;
      }
      ctx.beginPath();
      ctx.arc(cx, cy, radius * 0.62, 0, Math.PI * 2);
      ctx.fillStyle = "#ffffff";
      ctx.fill();
    });
  },

  chrome() {
    return {
      preset: this.preset,
      scope: this.scope,
      module: this.module,
      breakdown: this.breakdown,
      picker: this.scope === "SELECTED" ? this.picker : [],
    };
  },

  currentQuery(): LedgerQuery | null {
    if (this.scope === "SELECTED" && this.checkedIds.length === 0) return null;
    if (this.preset === "CUSTOM" && (this.customFrom.length === 0 || this.customTo.length === 0)) return null;
    return {
      preset: this.preset,
      from: this.preset === "CUSTOM" ? this.customFrom : undefined,
      to: this.preset === "CUSTOM" ? this.customTo : undefined,
      accountScope: this.scope,
      accountIds: this.scope === "SELECTED" ? this.checkedIds : undefined,
    };
  },
});

function drillFromDataset(dataset: Record<string, unknown>): LedgerDrill | null {
  const title = dataset["title"];
  const kind = dataset["drill"];
  if (typeof title !== "string" || title.length === 0) return null;
  if (kind === "uncategorized") return { kind: "uncategorized", title };
  if (kind === "untagged") return { kind: "untagged", title };
  const id = Number(dataset["id"]);
  if (!Number.isSafeInteger(id) || id <= 0) return null;
  if (kind === "category") return { kind: "category", categoryId: id, title };
  if (kind === "tag") return { kind: "tag", tagId: id, title, tagMode: "TREE" };
  return null;
}
