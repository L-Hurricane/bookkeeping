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
import { flowRows, type LedgerAmountRow } from "../../statistics/ledger-view";

const LOAD_ERROR = "统计暂时没有读取成功";
const CONTRACT_ERROR = "统计响应无法识别";

type ModuleName = "NET" | LedgerFlowType;

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
  picker: [] as PickerAccount[],
  selectedTagIds: [] as number[],

  data: {
    phase: "loading" as "loading" | "ready" | "error" | "choose",
    errorMessage: "",
    preset: "MONTH" as LedgerPreset,
    scope: "ACTIVE" as LedgerAccountScope,
    module: "NET" as ModuleName,
    rangeLabel: "",
    picker: [] as PickerAccount[],
    openingLabel: "",
    closingLabel: "",
    changeLabel: "",
    openingTone: "",
    closingTone: "",
    changeTone: "",
    points: [] as Array<{ date: string; amountLabel: string; tone: string }>,
    netLabel: "",
    netTone: "",
    categories: [] as LedgerAmountRow[],
    tags: [] as LedgerAmountRow[],
    emptyFlow: false,
    selectedTagCount: 0,
    showAnalysis: false,
    customFrom: "",
    customTo: "",
  },

  onLoad(query: Record<string, string | undefined>) {
    const accountId = Number(query.accountId);
    this.aiAccountId = Number.isSafeInteger(accountId) && accountId > 0 ? accountId : null;
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
    } catch (error) {
      if (!this.pageAlive || error instanceof ApiError && error.status === 401) return;
      this.picker = [];
    }
    await this.load();
  },

  onSelectPreset(event: WechatMiniprogram.BaseEvent) {
    const preset = event.currentTarget.dataset["preset"];
    if (preset !== "MONTH" && preset !== "QUARTER" && preset !== "YEAR" && preset !== "CUSTOM") return;
    if (preset === "CUSTOM" && (this.customFrom.length === 0 || this.customTo.length === 0)) {
      if (this.report === null) return;
      this.customFrom = this.report.from;
      this.customTo = this.report.to;
    }
    this.preset = preset;
    void this.load();
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
      points: net.points.map((point) => ({
        date: point.date,
        amountLabel: formatMoneyLabel(point.amount),
        tone: moneyToneClass(point.amount),
      })),
      netLabel: this.module === "NET" ? "" : formatMoneyLabel(flow.net),
      netTone: this.module === "NET" ? "" : moneyToneClass(flow.net),
      categories,
      tags,
      emptyFlow: this.module !== "NET" && flow.net === "0.00" && categories.length === 0,
      selectedTagCount: this.selectedTagIds.length,
      showAnalysis: this.aiAccountId !== null,
      customFrom: this.customFrom,
      customTo: this.customTo,
    });
  },

  chrome() {
    return { preset: this.preset, scope: this.scope, module: this.module, picker: this.scope === "SELECTED" ? this.picker : [] };
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
