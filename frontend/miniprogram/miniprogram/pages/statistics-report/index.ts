import { moneyToneClass } from "../../account/money-tone";
import { ApiError } from "../../http/client";
import { formatMoneyLabel } from "../../ledger/ledger";
import { accountApi, ledgerStatisticsApi } from "../../services/runtime";
import {
  accountChoiceGroups,
  rememberStatisticsSelection,
  retainKnownAccountIds,
  statisticsSelection,
  type AccountChoiceGroup,
} from "../../statistics/account-filter";
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
import { flowRows, ringGeometry, ringHit, ringSegments, ringSweeps, ringTouchPoint, type LedgerAmountRow, type RingSegment } from "../../statistics/ledger-view";
import { lineYs } from "../../statistics/line-layout";
import { allTransactionsPath } from "../../ledger/feed-contract";

const LOAD_ERROR = "统计暂时没有读取成功";
const CONTRACT_ERROR = "统计响应无法识别";

type ModuleName = "NET" | LedgerFlowType;
type BreakdownName = "CATEGORY" | "TAG";
type ReportView = "net" | "income-category" | "income-tag" | "expense-category" | "expense-tag";

const REPORT_VIEWS: Record<ReportView, { module: ModuleName; breakdown: BreakdownName; title: string }> = {
  net: { module: "NET", breakdown: "CATEGORY", title: "净值趋势" },
  "income-category": { module: "INCOME", breakdown: "CATEGORY", title: "收入分类对比" },
  "income-tag": { module: "INCOME", breakdown: "TAG", title: "收入标签对比" },
  "expense-category": { module: "EXPENSE", breakdown: "CATEGORY", title: "支出分类对比" },
  "expense-tag": { module: "EXPENSE", breakdown: "TAG", title: "支出标签对比" },
};

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
  report: null as LedgerReport | null,
  module: "NET" as ModuleName,
  breakdown: "CATEGORY" as BreakdownName,
  picker: [] as PickerAccount[],
  accountSheetOpen: false,
  accountQuery: "",
  draftIds: [] as number[],
  groupCollapsed: { active: false, archived: false } as Record<string, boolean>,
  ringSegments: [] as RingSegment[],
  ringPickKey: "",
  tagPickKey: "",

  data: {
    phase: "loading" as "loading" | "ready" | "error" | "choose",
    invalid: false,
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
    points: [] as Array<{ date: string; amount: string; amountLabel: string; tone: string; dateLabel: string }>,
    dailyPoints: [] as Array<{ date: string; amount: string; amountLabel: string; tone: string; dateLabel: string }>,
    selectedDate: "",
    netLabel: "",
    netTone: "",
    categories: [] as LedgerAmountRow[],
    tags: [] as LedgerAmountRow[],
    emptyFlow: false,
    customFrom: "",
    customTo: "",
    moreOpen: false,
    ringNote: "",
    ringSegments: [] as RingSegment[],
    ringPick: null as { title: string; amountLabel: string; share: string } | null,
    ringPickKey: "",
    ringLocateNote: "",
    tagPick: null as { title: string; amountLabel: string; detail: string } | null,
    tagPickKey: "",
    selectedCount: 0,
    accountSheetOpen: false,
    accountQuery: "",
    accountGroups: [] as AccountChoiceGroup[],
  },

  onLoad(query: Record<string, string | undefined>) {
    const view = reportView(query.view);
    if (view === null) {
      this.setData({ phase: "error", invalid: true, errorMessage: "统计页面参数无效" });
      return;
    }
    this.module = view.module;
    this.breakdown = view.breakdown;
    const saved = statisticsSelection();
    this.preset = saved.preset;
    this.scope = saved.scope;
    this.checkedIds = [...saved.accountIds];
    this.customFrom = saved.customFrom;
    this.customTo = saved.customTo;
    wx.setNavigationBarTitle({ title: view.title });
    void this.prepare();
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
      this.checkedIds = retainKnownAccountIds(this.checkedIds, this.picker.map((account) => account.id));
      this.picker = this.picker.map((account) => ({ ...account, checked: this.checkedIds.includes(account.id) }));
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
    if (scope === "SELECTED") {
      this.openAccountSheet();
      return;
    }
    this.scope = scope;
    this.accountSheetOpen = false;
    void this.load();
  },

  openAccountSheet() {
    this.draftIds = [...this.checkedIds];
    this.accountQuery = "";
    this.accountSheetOpen = true;
    this.publishAccountSheet();
  },

  onOpenAccountSheet() {
    this.openAccountSheet();
  },

  onAccountQuery(event: WechatMiniprogram.Input) {
    this.accountQuery = event.detail.value;
    this.publishAccountSheet();
  },

  onToggleGroup(event: WechatMiniprogram.BaseEvent) {
    const key = event.currentTarget.dataset["key"];
    if (key !== "active" && key !== "archived") return;
    this.groupCollapsed = { ...this.groupCollapsed, [key]: !this.groupCollapsed[key] };
    this.publishAccountSheet();
  },

  onToggleDraftAccount(event: WechatMiniprogram.BaseEvent) {
    const id = Number(event.currentTarget.dataset["id"]);
    if (!Number.isSafeInteger(id)) return;
    this.draftIds = this.draftIds.includes(id) ? this.draftIds.filter((item) => item !== id) : [...this.draftIds, id];
    this.publishAccountSheet();
  },

  onSelectAllAccounts() {
    this.draftIds = this.picker.map((account) => account.id);
    this.publishAccountSheet();
  },

  onClearAccounts() {
    this.draftIds = [];
    this.publishAccountSheet();
  },

  onCancelAccounts() {
    this.accountSheetOpen = false;
    this.setData({ accountSheetOpen: false });
  },

  onConfirmAccounts() {
    this.checkedIds = [...this.draftIds];
    this.scope = "SELECTED";
    this.picker = this.picker.map((account) => ({ ...account, checked: this.checkedIds.includes(account.id) }));
    this.accountSheetOpen = false;
    void this.load();
  },

  onHold() {
    return;
  },

  publishAccountSheet() {
    this.setData({
      accountSheetOpen: true,
      accountQuery: this.accountQuery,
      accountGroups: accountChoiceGroups(this.picker, this.draftIds, this.accountQuery, this.groupCollapsed),
    });
  },

  onRetry() {
    void this.load();
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
    rememberStatisticsSelection({
      preset: this.preset,
      customFrom: this.customFrom,
      customTo: this.customTo,
      scope: this.scope,
      accountIds: this.checkedIds,
    });
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
    const categories = this.module === "NET" ? [] : neutralRows(flowRows(flow, "category"));
    const ring = this.module !== "NET" && this.breakdown === "CATEGORY" ? ringSegments(flow) : null;
    this.ringSegments = ring ?? [];
    const chartPoints = net.points.map((point) => ({
      date: point.date,
      amount: point.amount,
      amountLabel: formatMoneyLabel(point.amount),
      tone: moneyToneClass(point.amount),
      dateLabel: point.date,
    }));
    const tags = this.module === "NET" ? [] : neutralRows(flowRows(flow, "tag"));
    const ringPick = this.ringSegments.find((segment) => segment.key === this.ringPickKey) ?? null;
    if (ringPick === null) this.ringPickKey = "";
    const tagPick = tags.find((row) => row.key === this.tagPickKey) ?? null;
    if (tagPick === null) this.tagPickKey = "";
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
      points: chartPoints,
      dailyPoints: [...chartPoints].reverse(),
      selectedDate: chartPoints[chartPoints.length - 1]?.date ?? "",
      ringNote: ring === null && this.module !== "NET" && this.breakdown === "CATEGORY"
        ? "净额含负数或为零，环形图不按 100% 构成展示。"
        : "",
      ringSegments: this.ringSegments,
      ringPick: ringPick === null ? null : { title: ringPick.title, amountLabel: ringPick.amountLabel, share: ringPick.share },
      ringPickKey: this.ringPickKey,
      ringLocateNote: "",
      tagPick: tagPick === null ? null : { title: tagPick.title, amountLabel: tagPick.amountLabel, detail: tagPick.detail },
      tagPickKey: this.tagPickKey,
      netLabel: this.module === "NET" ? "" : formatMoneyLabel(flow.net),
      netTone: "money-neutral",
      categories,
      tags,
      emptyFlow: this.module !== "NET" && flow.net === "0.00" && (this.breakdown === "TAG" ? tags.length === 0 : categories.length === 0),
      customFrom: this.customFrom,
      customTo: this.customTo,
    }, () => {
      this.drawRing();
      this.drawLine();
    });
  },

  onSelectPoint(event: WechatMiniprogram.BaseEvent) {
    const date = event.currentTarget.dataset["date"];
    if (typeof date !== "string") return;
    this.setData({ selectedDate: date }, () => this.drawLine());
  },

  drawLine() {
    const points = this.data.points;
    if (this.module !== "NET" || points.length === 0) return;
    wx.createSelectorQuery().in(this).select("#net-line").fields({ node: true, size: true }).exec((result) => {
      const node = result?.[0] as { node?: WechatMiniprogram.Canvas; width?: number; height?: number } | undefined;
      if (node?.node === undefined || node.width === undefined || node.height === undefined) return;
      const canvas = node.node;
      const ctx = canvas.getContext("2d");
      const dpr = wx.getWindowInfo().pixelRatio || 1;
      canvas.width = node.width * dpr;
      canvas.height = node.height * dpr;
      ctx.scale(dpr, dpr);
      ctx.clearRect(0, 0, node.width, node.height);
      const height = node.height;
      const ys = lineYs(points.map((point) => point.amount), height);
      const step = points.length === 1 ? 0 : (node.width - 24) / (points.length - 1);
      ctx.beginPath();
      ctx.strokeStyle = "#3d5a80";
      ctx.lineWidth = 2;
      points.forEach((point, index) => {
        const x = 12 + step * index;
        const y = ys[index] ?? height / 2;
        if (index === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
        void point;
      });
      ctx.stroke();
      const selected = points.findIndex((point) => point.date === this.data.selectedDate);
      const mark = selected >= 0 ? selected : points.length - 1;
      const x = 12 + step * mark;
      const y = ys[mark] ?? height / 2;
      ctx.beginPath();
      ctx.fillStyle = "#3d5a80";
      ctx.arc(x, y, 4, 0, Math.PI * 2);
      ctx.fill();
    });
  },

  onRingTap(event: { detail?: { x?: number; y?: number }; touches?: Array<{ clientX: number; clientY: number }>; changedTouches?: Array<{ clientX: number; clientY: number }> }) {
    const touch = event.changedTouches?.[0] ?? event.touches?.[0];
    wx.createSelectorQuery().in(this).select("#category-ring").fields({ node: true, size: true, rect: true }).exec((result) => {
      const node = result?.[0] as { width?: number; height?: number; left?: number; top?: number } | undefined;
      const width = node?.width;
      const height = node?.height;
      if (width === undefined || height === undefined) {
        this.setData({ ringLocateNote: "点到的位置无法对应扇区。" });
        return;
      }
      const point = ringTouchPoint(event.detail?.x, event.detail?.y, touch, { width, height, left: node?.left, top: node?.top });
      if (point === null) {
        this.setData({ ringLocateNote: "点到的位置无法对应扇区。" });
        return;
      }
      const hit = ringHit(this.ringSegments, point.x, point.y, width, height);
      if (hit.kind === "miss") {
        this.setData({ ringLocateNote: "这个位置没有对应的分类扇区。" });
        return;
      }
      if (hit.kind === "hole") {
        this.ringPickKey = "";
        this.setData({ ringPick: null, ringPickKey: "", ringLocateNote: "" }, () => this.drawRing());
        return;
      }
      const segment = this.ringSegments[hit.index];
      if (segment === undefined) {
        this.setData({ ringLocateNote: "点到的位置无法对应扇区。" });
        return;
      }
      this.ringPickKey = segment.key;
      this.setData({
        ringPick: { title: segment.title, amountLabel: segment.amountLabel, share: segment.share },
        ringPickKey: segment.key,
        ringLocateNote: "",
      }, () => this.drawRing());
    });
  },

  onPickTagBar(event: WechatMiniprogram.BaseEvent) {
    const key = event.currentTarget.dataset["key"];
    if (typeof key !== "string" || key.length === 0) return;
    const row = this.data.tags.find((item) => item.key === key);
    if (row === undefined) return;
    this.tagPickKey = key;
    this.setData({
      tagPickKey: key,
      tagPick: { title: row.title, amountLabel: row.amountLabel, detail: row.detail },
    });
  },

  drawRing() {
    const segments = this.ringSegments;
    if (segments.length === 0) return;
    wx.createSelectorQuery().in(this).select("#category-ring").fields({ node: true, size: true }).exec((result) => {
      const node = result?.[0] as { node?: WechatMiniprogram.Canvas; width?: number; height?: number } | undefined;
      if (node?.node === undefined || node.width === undefined || node.height === undefined) return;
      const geometry = ringGeometry(node.width, node.height);
      if (geometry === null) return;
      const canvas = node.node;
      const ctx = canvas.getContext("2d");
      const dpr = wx.getWindowInfo().pixelRatio || 1;
      canvas.width = node.width * dpr;
      canvas.height = node.height * dpr;
      ctx.scale(dpr, dpr);
      const colors = ["#3d5a80", "#c47b2b", "#6b5b95", "#7a8fa6", "#8d6e63", "#5c7a6e"];
      ctx.clearRect(0, 0, node.width, node.height);
      for (const sweep of ringSweeps(segments)) {
        const segment = segments[sweep.index];
        if (segment === undefined) continue;
        ctx.beginPath();
        ctx.moveTo(geometry.cx, geometry.cy);
        ctx.arc(geometry.cx, geometry.cy, geometry.radius, sweep.start, sweep.end);
        ctx.closePath();
        ctx.globalAlpha = this.ringPickKey.length > 0 && segment.key !== this.ringPickKey ? 0.4 : 1;
        ctx.fillStyle = colors[sweep.index % colors.length] ?? "#8a8a8a";
        ctx.fill();
        if (segment.key === this.ringPickKey) {
          ctx.globalAlpha = 1;
          ctx.lineWidth = 3;
          ctx.strokeStyle = "#1a1a1a";
          ctx.stroke();
        }
      }
      ctx.globalAlpha = 1;
      ctx.beginPath();
      ctx.arc(geometry.cx, geometry.cy, geometry.hole, 0, Math.PI * 2);
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
      selectedCount: this.checkedIds.length,
      accountSheetOpen: this.accountSheetOpen,
      accountGroups: this.accountSheetOpen
        ? accountChoiceGroups(this.picker, this.draftIds, this.accountQuery, this.groupCollapsed)
        : [],
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

function reportView(value: string | undefined): { module: ModuleName; breakdown: BreakdownName; title: string } | null {
  if (value === "net" || value === "income-category" || value === "income-tag" || value === "expense-category" || value === "expense-tag") {
    return REPORT_VIEWS[value];
  }
  return null;
}

function neutralRows(rows: LedgerAmountRow[]): LedgerAmountRow[] {
  return rows.map((row) => ({ ...row, amountToneClass: "money-neutral" }));
}

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
