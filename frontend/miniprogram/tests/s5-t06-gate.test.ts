import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";
import { ApiError } from "../miniprogram/http/client";
import { ANALYSIS_QUOTA, analysisGenerateMessage } from "../miniprogram/analysis/screen";
import { ledgerAnalysisPagePath, monthlyAnalysisPagePath } from "../miniprogram/analysis/navigation";
import { currentBeijingMonth } from "../miniprogram/ledger/month";
import { parsePermanentDeletionPreview } from "../miniprogram/account/permanent-deletion";
import { statisticsPagePath } from "../miniprogram/statistics/drilldown";
import { ledgerStatisticsEntryPath } from "../miniprogram/statistics/drilldown";

const statisticsPage = readFileSync(new URL("../miniprogram/pages/statistics/index.ts", import.meta.url), "utf8");
const accountHome = readFileSync(new URL("../miniprogram/pages/account-home/index.ts", import.meta.url), "utf8");
const ledgerPage = readFileSync(new URL("../miniprogram/pages/monthly-analysis/index.ts", import.meta.url), "utf8");

function handler(source: string, name: string): string {
  const start = source.indexOf(`${name}(`);
  assert.notEqual(start, -1, name);
  const next = source.indexOf("\n  },", start);
  assert.notEqual(next, -1, name);
  return source.slice(start, next);
}

describe("s5-t06 miniprogram gate", () => {
  it("opens no-account statistics on the Beijing ledger month without a statistics filter", () => {
    const month = currentBeijingMonth(new Date("2026-10-26T04:00:00Z"));
    const path = ledgerAnalysisPagePath("MONTH", month);
    assert.equal(month, "2026-10");
    assert.equal(path, "/pages/monthly-analysis/index?scope=ledger&periodKind=MONTH&period=2026-10");
    assert.equal(path.includes("accountId"), false);
    assert.equal(path.includes("preset"), false);
    assert.equal(path.includes("accountScope"), false);
    assert.equal(path.includes("from="), false);
    assert.equal(path.includes("to="), false);
    assert.equal(ledgerStatisticsEntryPath(), "/pages/statistics/index");

    const openLedger = handler(statisticsPage, "onOpenLedgerAnalysis");
    assert.equal(openLedger.includes('ledgerAnalysisPagePath("MONTH", currentBeijingMonth(new Date()))'), true);
    assert.equal(openLedger.includes("preset"), false);
    assert.equal(openLedger.includes("accountScope"), false);
    assert.equal(openLedger.includes("customFrom"), false);
    assert.equal(openLedger.includes("report.from"), false);
    assert.equal(statisticsPage.includes('wx:else class="analysis-entry" bindtap="onOpenLedgerAnalysis"') ||
      readFileSync(new URL("../miniprogram/pages/statistics/index.wxml", import.meta.url), "utf8")
        .includes('bindtap="onOpenLedgerAnalysis"'), true);
  });

  it("keeps the account-home statistics path on account monthly analysis", () => {
    const homeStatistics = handler(accountHome, "onStatistics");
    assert.equal(homeStatistics.includes("wx.switchTab({ url: accountStatisticsTarget(this.accountId) })"), true);
    assert.equal(homeStatistics.includes("ledgerAnalysisPagePath"), false);
    const entry = statisticsPagePath(1001, "2026-10", "2020-01-01T00:00:00+08:00");
    assert.equal(entry.startsWith("/pages/statistics/index?accountId=1001&month=2026-10"), true);

    const openAccount = handler(statisticsPage, "onOpenAnalysis");
    assert.equal(openAccount.includes("monthlyAnalysisPagePath(accountId, currentBeijingMonth(new Date()))"), true);
    assert.equal(openAccount.includes("this.report"), false);
    assert.equal(openAccount.includes("ledgerAnalysisPagePath"), false);
    assert.equal(
      monthlyAnalysisPagePath(1001, "2026-10"),
      "/pages/monthly-analysis/index?accountId=1001&month=2026-10",
    );
  });

  it("represents ledger month and year, quota text, and generation errors", () => {
    assert.equal(ledgerPage.includes('periodKind !== "MONTH" && periodKind !== "YEAR"'), true);
    assert.equal(ledgerPage.includes('title: periodKind === "YEAR" ? "年度分析" : "月度分析"'), true);
    assert.equal(ledgerPage.includes("全账本包含正常和归档账户。每天最多 3 次生成，查看已有报告不计入次数。"), true);
    assert.equal(ledgerPage.includes("analysisGenerateMessage(error)"), true);
    assert.equal(analysisGenerateMessage(new ApiError(409, "QUOTA_EXCEEDED", "server")), ANALYSIS_QUOTA);
    assert.equal(analysisGenerateMessage(new ApiError(409, "EMPTY_MONTH", "server")), "当前月份没有可分析的收支记录。所指是这个月的账本还没有收入或支出。");
    assert.equal(analysisGenerateMessage(new ApiError(409, "EMPTY_PERIOD", "server")), "当前期间没有可分析的收支记录。所指是这个期间的账本还没有收入或支出。");
    assert.equal(analysisGenerateMessage(new ApiError(502, "GENERATION_FAILED", "server")), "分析没有完成。这不是账本数据出错。");
  });

  it("requires ledgerReportCount before a deletion preview is accepted", () => {
    const body = {
      transactionCount: 1,
      adjustmentCount: 0,
      transferCount: 0,
      analysisSnapshotCount: 1,
      analysisGenerationCount: 1,
      affectedAccounts: [],
      previewToken: "preview-token",
    };
    assert.equal(parsePermanentDeletionPreview(body), null);
    const preview = parsePermanentDeletionPreview({ ...body, ledgerReportCount: 2 });
    assert.equal(preview?.ledgerReportCount, 2);
  });
});
