import { currentBeijingMonth } from "../../ledger/month";
import { ledgerAnalysisPagePath, monthlyAnalysisPagePath } from "../../analysis/navigation";
import { takeStatisticsAccount } from "../../statistics/tab-context";

type ReportView = "net" | "income-category" | "income-tag" | "expense-category" | "expense-tag";

Page({
  aiAccountId: null as number | null,

  data: {
    showAnalysis: false,
  },

  onLoad(query: Record<string, string | undefined>) {
    const accountId = Number(query.accountId);
    this.aiAccountId = Number.isSafeInteger(accountId) && accountId > 0 ? accountId : null;
    this.setData({ showAnalysis: this.aiAccountId !== null });
  },

  onShow() {
    const accountId = takeStatisticsAccount();
    if (accountId === undefined) return;
    this.aiAccountId = accountId;
    this.setData({ showAnalysis: accountId !== null });
  },

  onTabItemTap() {
    this.aiAccountId = null;
    this.setData({ showAnalysis: false });
  },

  onOpenReport(event: WechatMiniprogram.BaseEvent) {
    const view = event.currentTarget.dataset["view"];
    if (!isReportView(view)) return;
    wx.navigateTo({ url: `/pages/statistics-report/index?view=${view}` });
  },

  onOpenAnalysis() {
    const accountId = this.aiAccountId;
    if (accountId === null) return;
    wx.navigateTo({ url: monthlyAnalysisPagePath(accountId, currentBeijingMonth(new Date())) });
  },

  onOpenLedgerAnalysis() {
    wx.navigateTo({ url: ledgerAnalysisPagePath("MONTH", currentBeijingMonth(new Date())) });
  },
});

function isReportView(value: unknown): value is ReportView {
  return value === "net" || value === "income-category" || value === "income-tag"
    || value === "expense-category" || value === "expense-tag";
}
