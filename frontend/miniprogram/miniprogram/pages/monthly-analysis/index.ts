import { formatMonthLabel, nextMonth, previousMonth } from "../../ledger/month";
import { ApiError } from "../../http/client";
import { isLedgerPeriod, type LedgerAnalysis } from "../../analysis/ledger-contract";
import { monthlyAnalysisApi, ledgerAnalysisApi } from "../../services/runtime";
import { parseAnalysisContext, type AnalysisPageContext } from "../../analysis/navigation";
import type { MonthlyAnalysis } from "../../analysis/contract";
import {
  analysisFactLines,
  analysisGenerateMessage,
  analysisLoadMessage,
  ANALYSIS_REFRESH,
  analysisAiCaution,
  analysisNotice,
  analysisSections,
  applyGenerate,
  beginGenerate,
  canGenerateAnalysis,
  canRefreshAnalysis,
  failAnalysis,
  openAnalysis,
  rejectGenerate,
  requestAnalysis,
  settleAnalysis,
  type AnalysisFactLine,
  type AnalysisScreen,
  type AnalysisSection,
} from "../../analysis/screen";

Page({
  context: null as AnalysisPageContext | null,
  ledger: null as { periodKind: "MONTH" | "YEAR" } | null,
  ledgerView: null as LedgerAnalysis | null,
  screen: openAnalysis("2000-01"),
  pageAlive: true,

  data: {
    invalid: false,
    ledgerMode: false,
    quotaNotice: "",
    phase: "loading" as AnalysisScreen["phase"],
    monthLabel: "",
    notice: "",
    facts: [] as AnalysisFactLine[],
    sections: [] as AnalysisSection[],
    aiCaution: "",
    canGenerate: false,
    canRefreshStatus: false,
    needsRefresh: false,
    submitting: false,
    actionMessage: "",
    errorMessage: "",
  },

  onLoad(query: Record<string, string | undefined>) {
    if (query.scope === "ledger") {
      const periodKind = query.periodKind;
      const period = query.period;
      if ((periodKind !== "MONTH" && periodKind !== "YEAR") || period === undefined || !isLedgerPeriod(periodKind, period)) {
        this.setData({ invalid: true, phase: "error", errorMessage: "页面参数不正确" });
        return;
      }
      this.ledger = { periodKind };
      this.screen = openAnalysis(period);
      wx.setNavigationBarTitle({ title: periodKind === "YEAR" ? "年度分析" : "月度分析" });
      return;
    }
    const context = parseAnalysisContext(query);
    if (context === null) {
      this.setData({ invalid: true, phase: "error", errorMessage: "页面参数不正确" });
      return;
    }
    this.context = context;
    this.screen = openAnalysis(context.month);
  },

  onShow() {
    if (this.context === null && this.ledger === null) {
      return;
    }
    void this.load(this.screen.month);
  },

  onUnload() {
    this.pageAlive = false;
  },

  onPreviousMonth() {
    if (this.context === null && this.ledger === null) {
      return;
    }
    void this.load(this.ledger?.periodKind === "YEAR" ? shiftYear(this.screen.month, -1) : previousMonth(this.screen.month));
  },

  onNextMonth() {
    if (this.context === null && this.ledger === null) {
      return;
    }
    void this.load(this.ledger?.periodKind === "YEAR" ? shiftYear(this.screen.month, 1) : nextMonth(this.screen.month));
  },

  onRetry() {
    if (this.context === null && this.ledger === null) {
      return;
    }
    void this.load(this.screen.month);
  },

  onSelectLedgerKind(event: WechatMiniprogram.BaseEvent) {
    const kind = event.currentTarget.dataset["kind"];
    if (this.ledger === null || (kind !== "MONTH" && kind !== "YEAR") || kind === this.ledger.periodKind) return;
    const period = kind === "YEAR" ? this.screen.month.slice(0, 4) : `${this.screen.month.slice(0, 4)}-01`;
    this.ledger = { periodKind: kind };
    wx.setNavigationBarTitle({ title: kind === "YEAR" ? "年度分析" : "月度分析" });
    void this.load(period);
  },

  async onGenerate() {
    const context = this.context;
    const attempt = beginGenerate(this.screen);
    if ((context === null && this.ledger === null) || attempt === null) {
      return;
    }
    this.screen = attempt.screen;
    this.publish();
    try {
      if (this.ledger === null) {
        const result = await monthlyAnalysisApi.generate(context!.accountId, attempt.month, attempt.expectedFingerprint);
        this.ledgerView = null;
        this.screen = applyGenerate(this.screen, attempt, result.analysis, result.outcome, this.pageAlive);
      } else {
        const result = await generateLedger(this.ledger.periodKind, attempt.month, attempt.expectedFingerprint);
        this.ledgerView = result.ledger;
        this.screen = applyGenerate(this.screen, attempt, result.analysis, result.outcome, this.pageAlive);
      }
    } catch (error) {
      if (!this.pageAlive || error instanceof ApiError && error.status === 401) {
        return;
      }
      this.screen = rejectGenerate(this.screen, attempt, analysisGenerateMessage(error), this.pageAlive);
    }
    if (!this.pageAlive) {
      return;
    }
    this.publish();
  },

  async load(month: string) {
    const context = this.context;
    if (context === null && this.ledger === null) {
      return;
    }
    const requested = requestAnalysis(this.screen, month);
    this.screen = requested.screen;
    this.publish();
    try {
      const loaded = this.ledger === null
        ? { analysis: await monthlyAnalysisApi.load(context!.accountId, month), ledger: null }
        : await loadLedger(this.ledger.periodKind, month);
      const analysis = loaded.analysis;
      this.ledgerView = loaded.ledger;
      if (!this.pageAlive) {
        return;
      }
      this.screen = settleAnalysis(this.screen, requested.generation, analysis, this.pageAlive, requested.duringSubmit);
    } catch (error) {
      if (!this.pageAlive || error instanceof ApiError && error.status === 401) {
        return;
      }
      this.screen = failAnalysis(this.screen, requested.generation, analysisLoadMessage(error), this.pageAlive);
    }
    if (!this.pageAlive) {
      return;
    }
    this.publish();
  },

  publish() {
    const analysis = this.screen.analysis;
    this.setData({
      invalid: false,
      ledgerMode: this.ledger !== null,
      quotaNotice: this.ledger === null ? "" : "全账本包含正常和归档账户。每天最多 3 次生成，查看已有报告不计入次数。",
      phase: this.screen.phase,
      monthLabel: this.ledger?.periodKind === "YEAR" ? `${this.screen.month}年` : formatMonthLabel(this.screen.month),
      notice: analysis === null ? "" : analysisNotice(analysis, new Date()),
      facts: this.ledgerView === null ? (analysis === null ? [] : analysisFactLines(analysis)) : ledgerFactLines(this.ledgerView),
      sections: this.ledgerView === null ? (analysis === null ? [] : analysisSections(analysis)) : ledgerSections(analysis),
      aiCaution: analysis === null ? "" : analysisAiCaution(analysis),
      canGenerate: canGenerateAnalysis(this.screen),
      canRefreshStatus: canRefreshAnalysis(this.screen),
      needsRefresh: this.screen.actionMessage === ANALYSIS_REFRESH,
      submitting: this.screen.submitting,
      actionMessage: this.screen.actionMessage,
      errorMessage: this.screen.error,
    });
  },
});

function shiftYear(period: string, delta: number): string {
  return String(Number(period) + delta).padStart(4, "0");
}

async function loadLedger(periodKind: "MONTH" | "YEAR", period: string): Promise<{ analysis: MonthlyAnalysis; ledger: LedgerAnalysis }> {
  const ledger = await ledgerAnalysisApi.load(periodKind, period);
  return { analysis: asMonthly(ledger), ledger };
}

async function generateLedger(periodKind: "MONTH" | "YEAR", period: string, fingerprint: string): Promise<{
  analysis: MonthlyAnalysis;
  outcome: "REUSED" | "SUCCESS";
  ledger: LedgerAnalysis;
}> {
  const result = await ledgerAnalysisApi.generate(periodKind, period, fingerprint);
  return {
    analysis: asMonthly(result.analysis),
    outcome: result.outcome === "REUSED" ? "REUSED" : "SUCCESS",
    ledger: result.analysis,
  };
}

function asMonthly(ledger: LedgerAnalysis): MonthlyAnalysis {
  return {
    month: ledger.period,
    fingerprint: ledger.fingerprint,
    emptyMonth: ledger.emptyPeriod,
    viewState: ledger.viewState,
    facts: {
      timeZone: "Asia/Shanghai",
      currency: "CNY",
      netIncome: ledger.incomeNet,
      netExpense: ledger.expenseNet,
      surplus: ledger.change,
      incomeTransactionCount: ledger.incomeCount,
      expenseTransactionCount: ledger.expenseCount,
      incomeCategories: [],
      expenseCategories: [],
      uncategorizedIncome: { transactionCount: 0, netAmount: "0.00" },
      uncategorizedExpense: { transactionCount: 0, netAmount: "0.00" },
      keyExpenses: [],
    },
    snapshot: ledger.snapshot,
    generation: ledger.generation,
  };
}

function ledgerFactLines(ledger: LedgerAnalysis): AnalysisFactLine[] {
  return [
    { label: "期初净值", text: `${ledger.opening} 元` },
    { label: "期末净值", text: `${ledger.closing} 元` },
    { label: "净值变化", text: `${ledger.change} 元` },
    { label: "净收入", text: `${ledger.incomeNet} 元，${ledger.incomeCount} 笔` },
    { label: "净支出", text: `${ledger.expenseNet} 元，${ledger.expenseCount} 笔` },
    { label: "上一期间", text: `${ledger.priorPeriod} 净值变化 ${ledger.priorChange} 元` },
  ];
}

function ledgerSections(analysis: MonthlyAnalysis | null): AnalysisSection[] {
  const snapshot = analysis?.snapshot;
  if (snapshot === null || snapshot === undefined || analysis?.viewState === "NO_ANALYSIS" || analysis?.viewState === "EMPTY_MONTH") {
    return [];
  }
  return [
    { title: "期间净值", text: snapshot.monthlyOverview },
    { title: "收支结构", text: snapshot.expenseStructure },
    { title: "与上一期间对比", text: snapshot.surplusReasons },
    { title: "值得关注", text: snapshot.pointsToWatch },
  ];
}
