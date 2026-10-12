import { formatMonthLabel, nextMonth, previousMonth } from "../../ledger/month";
import { ApiError } from "../../http/client";
import { monthlyAnalysisApi } from "../../services/runtime";
import { parseAnalysisContext, type AnalysisPageContext } from "../../analysis/navigation";
import {
  analysisLoadMessage,
  analysisNotice,
  analysisSections,
  failAnalysis,
  openAnalysis,
  requestAnalysis,
  settleAnalysis,
  type AnalysisScreen,
  type AnalysisSection,
} from "../../analysis/screen";

Page({
  context: null as AnalysisPageContext | null,
  screen: openAnalysis("2000-01"),
  pageAlive: true,

  data: {
    invalid: false,
    phase: "loading" as AnalysisScreen["phase"],
    monthLabel: "",
    notice: "",
    sections: [] as AnalysisSection[],
    errorMessage: "",
  },

  onLoad(query: Record<string, string | undefined>) {
    const context = parseAnalysisContext(query);
    if (context === null) {
      this.setData({ invalid: true, phase: "error", errorMessage: "页面参数不正确" });
      return;
    }
    this.context = context;
    this.screen = openAnalysis(context.month);
  },

  onShow() {
    if (this.context === null) {
      return;
    }
    void this.load(this.screen.month);
  },

  onUnload() {
    this.pageAlive = false;
  },

  onPreviousMonth() {
    if (this.context === null) {
      return;
    }
    void this.load(previousMonth(this.screen.month));
  },

  onNextMonth() {
    if (this.context === null) {
      return;
    }
    void this.load(nextMonth(this.screen.month));
  },

  onRetry() {
    if (this.context === null) {
      return;
    }
    void this.load(this.screen.month);
  },

  async load(month: string) {
    const context = this.context;
    if (context === null) {
      return;
    }
    const requested = requestAnalysis(this.screen, month);
    this.screen = requested.screen;
    this.publish();
    try {
      const analysis = await monthlyAnalysisApi.load(context.accountId, month);
      this.screen = settleAnalysis(this.screen, requested.generation, analysis, this.pageAlive);
    } catch (error) {
      if (!this.pageAlive || error instanceof ApiError && error.status === 401) {
        return;
      }
      this.screen = failAnalysis(this.screen, requested.generation, analysisLoadMessage(error), this.pageAlive);
    }
    this.publish();
  },

  publish() {
    const analysis = this.screen.analysis;
    this.setData({
      invalid: false,
      phase: this.screen.phase,
      monthLabel: formatMonthLabel(this.screen.month),
      notice: analysis === null ? "" : analysisNotice(analysis),
      sections: analysis === null ? [] : analysisSections(analysis),
      errorMessage: this.screen.error,
    });
  },
});
