import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { parseMonthlyAnalysis, type AnalysisViewState, type MonthlyAnalysis } from "../miniprogram/analysis/contract";
import { monthlyAnalysisPagePath, parseAnalysisContext } from "../miniprogram/analysis/navigation";
import {
  ANALYSIS_AI_CAUTION,
  ANALYSIS_EMPTY_MONTH,
  ANALYSIS_FAILED,
  ANALYSIS_IN_PROGRESS,
  ANALYSIS_NETWORK_ERROR,
  ANALYSIS_NONE,
  ANALYSIS_QUOTA,
  ANALYSIS_REFRESH,
  ANALYSIS_REUSED,
  ANALYSIS_STALE,
  ANALYSIS_TIMEOUT,
  analysisAgeNotice,
  analysisAiCaution,
  analysisFactLines,
  analysisGenerateMessage,
  analysisLoadMessage,
  analysisNotice,
  analysisSections,
  applyGenerate,
  beginGenerate,
  canRefreshAnalysis,
  failAnalysis,
  openAnalysis,
  rejectGenerate,
  requestAnalysis,
  settleAnalysis,
} from "../miniprogram/analysis/screen";
import { ApiError, createHttpClient, type HttpTransportRequest } from "../miniprogram/http/client";
import { createMonthlyAnalysisApi, monthlyAnalysisPath } from "../miniprogram/services/analysis";
import { saveSession } from "../miniprogram/session/session";
import type { KeyValueStorage } from "../miniprogram/storage/key-value";

const overview = "2026年10月账本概况：净收入0.00元，0笔；净支出12.00元，1笔；结余-12.00元。\n本月账本没有收入记录。";
const seenAt = new Date("2026-10-10T08:00:00Z");

describe("monthly analysis GET", () => {
  it("requests the selected account and month and does not call another API", async () => {
    const requests: HttpTransportRequest[] = [];
    const api = apiFor((request) => {
      requests.push(request);
      return { statusCode: 200, data: body("CURRENT") };
    });
    const analysis = await api.load(1001, "2026-10");
    assert.equal(monthlyAnalysisPath(1001, "2026-10"), "/api/v1/accounts/1001/monthly-analysis?month=2026-10");
    assert.equal(requests.length, 1);
    assert.equal(requests[0]?.method, "GET");
    assert.equal(requests[0]?.url, "http://127.0.0.1:8080/api/v1/accounts/1001/monthly-analysis?month=2026-10");
    assert.equal(analysis.fingerprint, "1:abc");
    assert.equal(analysis.facts.netExpense, "12.00");
    assert.equal(analysis.snapshot?.monthlyOverview, overview);
  });

  it("rejects a statistics payload instead of pairing it with an analysis fingerprint", () => {
    assert.equal(parseMonthlyAnalysis({
      month: "2026-10",
      transactionCount: 1,
      netIncome: "0.00",
      netExpense: "12.00",
      surplus: "-12.00",
    }, "2026-10"), null);
  });

  it("shows the four sections for the current snapshot without rewriting them", () => {
    const analysis = parseMonthlyAnalysis(body("CURRENT"), "2026-10");
    assert.ok(analysis);
    const sections = analysisSections(analysis);
    assert.deepEqual(sections.map((section) => section.title), ["本月概况", "支出结构", "结余情况与可能原因", "值得关注"]);
    assert.equal(sections[0]?.text, overview);
    assert.equal(sections[2]?.text, "结余-12.00元。\n支出高于收入。");
    assert.equal(analysisNotice(analysis, seenAt), "");
    assert.equal(analysisAiCaution(analysis), ANALYSIS_AI_CAUTION);
  });

  it("marks a stale snapshot and still shows its original text", () => {
    const analysis = parseMonthlyAnalysis(body("STALE", { stale: true, fingerprint: "1:old" }), "2026-10");
    assert.ok(analysis);
    assert.equal(analysisNotice(analysis, seenAt), ANALYSIS_STALE);
    assert.equal(analysisSections(analysis)[0]?.text, overview);
    assert.equal(analysis.fingerprint, "1:abc");
    assert.notEqual(analysis.snapshot?.fingerprint, analysis.fingerprint);
  });

  it("does not invent a report when nothing has been generated", () => {
    const analysis = parseMonthlyAnalysis(body("NO_ANALYSIS", { snapshot: null, expenseTransactionCount: 1, emptyMonth: false }), "2026-10");
    assert.ok(analysis);
    assert.equal(analysisNotice(analysis, seenAt), ANALYSIS_NONE);
    assert.deepEqual(analysisSections(analysis), []);
    assert.equal(analysisAiCaution(analysis), "");
    assert.equal(analysis.snapshot, null);
  });

  it("explains an empty month as missing ledger records", () => {
    const analysis = parseMonthlyAnalysis(body("EMPTY_MONTH", {
      snapshot: null,
      incomeTransactionCount: 0,
      expenseTransactionCount: 0,
      emptyMonth: true,
      netIncome: "0.00",
      netExpense: "0.00",
      surplus: "0.00",
    }), "2026-10");
    assert.ok(analysis);
    assert.equal(analysisNotice(analysis, seenAt), ANALYSIS_EMPTY_MONTH);
    assert.deepEqual(analysisSections(analysis), []);
    assert.equal(analysisAiCaution(analysis), "");
  });

  it("shows in-progress and failed states, keeping an earlier report only when one exists", () => {
    const running = parseMonthlyAnalysis(body("GENERATION_IN_PROGRESS", {
      generation: { state: "IN_PROGRESS", active: true },
    }), "2026-10");
    const failed = parseMonthlyAnalysis(body("GENERATION_FAILED", {
      generation: { state: "FAILED", active: false },
    }), "2026-10");
    const failedWithoutReport = parseMonthlyAnalysis(body("GENERATION_FAILED", {
      snapshot: null,
      generation: { state: "FAILED", active: false },
    }), "2026-10");
    assert.ok(running && failed && failedWithoutReport);
    assert.equal(analysisNotice(running, seenAt), ANALYSIS_IN_PROGRESS);
    assert.equal(analysisSections(running).length, 4);
    assert.equal(analysisNotice(failed, seenAt), ANALYSIS_FAILED);
    assert.equal(analysisSections(failed).length, 4);
    assert.equal(analysisNotice(failedWithoutReport, seenAt), ANALYSIS_FAILED);
    assert.deepEqual(analysisSections(failedWithoutReport), []);
    assert.equal(analysisAiCaution(failedWithoutReport), "");
    assert.equal(analysisAiCaution(running), ANALYSIS_AI_CAUTION);
    assert.equal(parseMonthlyAnalysis(body("GENERATION_IN_PROGRESS", {
      generation: { state: "IN_PROGRESS", active: false },
    }), "2026-10"), null);
  });

  it("reports a network failure without exposing an internal error", async () => {
    const api = apiFor(() => {
      throw new Error("socket hang up at /tmp/internal");
    });
    await assert.rejects(() => api.load(1001, "2026-10"), (error: unknown) => {
      assert.equal(analysisLoadMessage(error), ANALYSIS_NETWORK_ERROR);
      assert.equal(String(error).includes("/tmp/internal"), false);
      return true;
    });
  });

  it("opens the analysis page for the statistics month", () => {
    assert.equal(
      monthlyAnalysisPagePath(1001, "2026-09"),
      "/pages/monthly-analysis/index?accountId=1001&month=2026-09",
    );
    assert.deepEqual(parseAnalysisContext({ accountId: "1001", month: "2026-09" }), { accountId: 1001, month: "2026-09" });
    assert.equal(parseAnalysisContext({ accountId: "0", month: "2026-09" }), null);
  });

  it("keeps null key-expense fields and still shows the four sections", async () => {
    const requests: HttpTransportRequest[] = [];
    const api = apiFor((request) => {
      requests.push(request);
      return { statusCode: 200, data: body("STALE", { stale: true, fingerprint: "1:old", keyExpenses: [keyExpense()] }) };
    });
    const analysis = await api.load(1001, "2026-10");
    assert.equal(requests.length, 1);
    assert.equal(requests[0]?.method, "GET");
    assert.equal(analysis.facts.keyExpenses[0]?.categoryPath, null);
    assert.equal(analysis.facts.keyExpenses[0]?.transactionName, null);
    assert.equal(analysisSections(analysis).length, 4);
    assert.equal(analysisSections(analysis)[2]?.text, "结余-12.00元。\n支出高于收入。");
  });

  it("keeps a present category path and a present transaction name", () => {
    const analysis = parseMonthlyAnalysis(body("CURRENT", {
      keyExpenses: [keyExpense({ categoryPath: "餐饮 > 早餐", transactionName: "豆浆" })],
    }), "2026-10");
    assert.equal(analysis?.facts.keyExpenses[0]?.categoryPath, "餐饮 > 早餐");
    assert.equal(analysis?.facts.keyExpenses[0]?.transactionName, "豆浆");
    assert.equal(analysisSections(analysis as MonthlyAnalysis).length, 4);
  });

  it("accepts either nullable key-expense field alone", () => {
    const uncategorized = parseMonthlyAnalysis(body("CURRENT", {
      keyExpenses: [keyExpense({ transactionName: "未分类支出" })],
    }), "2026-10");
    const unnamed = parseMonthlyAnalysis(body("CURRENT", {
      keyExpenses: [keyExpense({ categoryPath: "购物" })],
    }), "2026-10");
    const omitted = parseMonthlyAnalysis(body("CURRENT", {
      keyExpenses: [{ transactionType: "EXPENSE", signedAmount: "-12.00", businessTime: "2026-10-02T12:00:00+08:00" }],
    }), "2026-10");
    assert.equal(uncategorized?.facts.keyExpenses[0]?.categoryPath, null);
    assert.equal(uncategorized?.facts.keyExpenses[0]?.transactionName, "未分类支出");
    assert.equal(unnamed?.facts.keyExpenses[0]?.categoryPath, "购物");
    assert.equal(unnamed?.facts.keyExpenses[0]?.transactionName, null);
    assert.equal(omitted?.facts.keyExpenses[0]?.categoryPath, null);
    assert.equal(omitted?.facts.keyExpenses[0]?.transactionName, null);
  });

  it("rejects a key expense whose nullable field has the wrong type", () => {
    for (const keyExpenses of [
      [keyExpense({ categoryPath: 12 })],
      [keyExpense({ categoryPath: { name: "购物" } })],
      [keyExpense({ transactionName: 12 })],
      [keyExpense({ transactionName: { name: "豆浆" } })],
      [keyExpense({ categoryPath: "" })],
      [keyExpense({ transactionName: "" })],
    ]) {
      assert.equal(parseMonthlyAnalysis(body("CURRENT", { keyExpenses }), "2026-10"), null);
    }
  });

  it("marks an older snapshot while analysis is running or has failed", () => {
    const current = parseMonthlyAnalysis(body("CURRENT"), "2026-10");
    const stale = parseMonthlyAnalysis(body("STALE", { stale: true, fingerprint: "1:old" }), "2026-10");
    const running = parseMonthlyAnalysis(body("GENERATION_IN_PROGRESS", {
      stale: true,
      fingerprint: "1:old",
      generation: { state: "IN_PROGRESS", active: true },
    }), "2026-10");
    const failed = parseMonthlyAnalysis(body("GENERATION_FAILED", {
      stale: true,
      fingerprint: "1:old",
      generation: { state: "FAILED", active: false },
    }), "2026-10");
    assert.ok(current && stale && running && failed);
    assert.equal(current.snapshot?.stale, false);
    assert.equal(analysisNotice(current, seenAt), "");
    assert.equal(analysisNotice(stale, seenAt), ANALYSIS_STALE);
    assert.equal(analysisNotice(running, seenAt), `${ANALYSIS_IN_PROGRESS}\n${ANALYSIS_STALE}`);
    assert.equal(analysisNotice(failed, seenAt), `${ANALYSIS_FAILED}\n${ANALYSIS_STALE}`);
    assert.equal(analysisSections(running).length, 4);
    assert.equal(analysisSections(failed)[0]?.text, overview);
  });

  it("reminds that a matching report is old without hiding it or calling generate", async () => {
    const requests: HttpTransportRequest[] = [];
    const old = body("CURRENT", { generatedAt: "2026-10-01T04:00:00Z" });
    const api = apiFor((request) => {
      requests.push(request);
      return { statusCode: 200, data: old };
    });
    const analysis = await api.load(1001, "2026-10");
    const now = new Date("2026-10-09T04:00:00Z");
    assert.deepEqual(requests.map((request) => request.method), ["GET"]);
    assert.equal(analysis.viewState, "CURRENT");
    assert.equal(analysis.snapshot?.stale, false);
    assert.equal(analysisNotice(analysis, now), "这份分析生成于2026年10月1日，时间较早，可能尚未反映后续的模型能力更新。");
    assert.equal(analysisSections(analysis).length, 4);
    assert.equal(analysisSections(analysis)[0]?.text, overview);
    assert.equal(analysisNotice(analysis, new Date("2026-10-08T04:00:00Z")), "");
    assert.equal(analysisAgeNotice("not-a-time", now), null);
  });

  it("marks a freshly generated unmatched report as stale data", () => {
    const analysis = parseMonthlyAnalysis(body("STALE", {
      stale: true,
      fingerprint: "1:old",
      generatedAt: "2026-10-12T06:00:00Z",
    }), "2026-10");
    assert.ok(analysis);
    const notice = analysisNotice(analysis, new Date("2026-10-12T06:00:00Z"));
    assert.equal(notice, ANALYSIS_STALE);
    assert.equal(notice.includes("模型能力"), false);
    assert.equal(analysisSections(analysis).length, 4);
  });

  it("ignores a late response from another month", () => {
    const first = requestAnalysis(openAnalysis("2026-10"), "2026-10");
    const second = requestAnalysis(first.screen, "2026-09");
    const late = settleAnalysis(second.screen, first.generation, parseMonthlyAnalysis(body("CURRENT"), "2026-10") as MonthlyAnalysis, true);
    assert.equal(late.phase, "loading");
    assert.equal(late.month, "2026-09");
    assert.equal(late.analysis, null);
    const failed = failAnalysis(second.screen, second.generation, ANALYSIS_NETWORK_ERROR, true);
    assert.equal(failed.phase, "error");
    assert.equal(failed.error, ANALYSIS_NETWORK_ERROR);
  });
});

describe("monthly analysis generate", () => {
  it("posts the fingerprint from the displayed GET and does not generate by itself", async () => {
    const requests: HttpTransportRequest[] = [];
    const api = apiFor((request) => {
      requests.push(request);
      if (request.method === "GET") {
        return { statusCode: 200, data: body("STALE", { stale: true, fingerprint: "1:old" }) };
      }
      return { statusCode: 200, data: { outcome: "SUCCESS", analysis: body("CURRENT") } };
    });
    const loaded = await api.load(1001, "2026-10");
    assert.deepEqual(analysisFactLines(loaded).map((line) => `${line.label} ${line.text}`), [
      "净收入 0.00 元，0 笔",
      "净支出 12.00 元，1 笔",
      "结余 -12.00 元",
    ]);
    const ready = settleAnalysis(requestAnalysis(openAnalysis("2026-10"), "2026-10").screen, 1, loaded, true);
    const attempt = beginGenerate(ready);
    assert.ok(attempt);
    assert.equal(attempt.expectedFingerprint, loaded.fingerprint);
    assert.equal(attempt.expectedFingerprint, "1:abc");
    assert.notEqual(attempt.expectedFingerprint, loaded.snapshot?.fingerprint);
    assert.equal(beginGenerate(attempt.screen), null);
    const result = await api.generate(1001, attempt.month, attempt.expectedFingerprint);
    const posted = JSON.parse(requests[1]?.data ?? "{}") as { month?: string; expectedFingerprint?: string };
    assert.deepEqual(requests.map((request) => request.method), ["GET", "POST"]);
    assert.equal(requests[1]?.url, "http://127.0.0.1:8080/api/v1/accounts/1001/monthly-analysis");
    assert.equal(posted.month, "2026-10");
    assert.equal(posted.expectedFingerprint, "1:abc");
    const applied = applyGenerate(attempt.screen, attempt, result.analysis, result.outcome, true);
    assert.equal(applied.submitting, false);
    assert.equal(applied.actionMessage, "");
    assert.equal(analysisSections(applied.analysis as MonthlyAnalysis).length, 4);
    assert.equal(analysisFactLines(applied.analysis as MonthlyAnalysis)[1]?.text, "12.00 元，1 笔");
  });

  it("keeps a reused report and the failure states without another post", () => {
    const ready = readyScreen("NO_ANALYSIS", { snapshot: null, expenseTransactionCount: 1, emptyMonth: false });
    const attempt = beginGenerate(ready);
    assert.ok(attempt);
    const reused = parseMonthlyAnalysis(body("CURRENT"), "2026-10");
    assert.ok(reused);
    const applied = applyGenerate(attempt.screen, attempt, reused, "REUSED", true);
    assert.equal(applied.actionMessage, ANALYSIS_REUSED);
    assert.equal(analysisSections(applied.analysis as MonthlyAnalysis)[0]?.text, overview);
    const blocked = readyScreen("EMPTY_MONTH", {
      snapshot: null,
      incomeTransactionCount: 0,
      expenseTransactionCount: 0,
      emptyMonth: true,
    });
    const running = readyScreen("GENERATION_IN_PROGRESS", { generation: { state: "IN_PROGRESS", active: true } });
    assert.equal(beginGenerate(blocked), null);
    assert.equal(beginGenerate(running), null);
    const refused = rejectGenerate(attempt.screen, attempt, analysisGenerateMessage(new ApiError(409, "REFRESH_REQUIRED", "server")), true);
    assert.equal(refused.actionMessage, ANALYSIS_REFRESH);
    assert.equal(refused.analysis?.fingerprint, ready.analysis?.fingerprint);
    assert.equal(beginGenerate(refused), null);
    assert.equal(analysisGenerateMessage(new ApiError(409, "IN_PROGRESS", "server")), ANALYSIS_IN_PROGRESS);
    assert.equal(analysisGenerateMessage(new ApiError(409, "QUOTA_EXCEEDED", "server")), ANALYSIS_QUOTA);
    assert.equal(analysisGenerateMessage(new ApiError(502, "GENERATION_FAILED", "stack /tmp/internal")), ANALYSIS_FAILED);
    assert.equal(analysisGenerateMessage(new ApiError(502, "GENERATION_FAILED", "stack /tmp/internal")).includes("/tmp/internal"), false);
  });

  it("drops a late generate response after the month changes", () => {
    const ready = readyScreen("NO_ANALYSIS", { snapshot: null, expenseTransactionCount: 1, emptyMonth: false });
    const attempt = beginGenerate(ready);
    assert.ok(attempt);
    const switched = requestAnalysis(attempt.screen, "2026-09");
    const late = applyGenerate(switched.screen, attempt, parseMonthlyAnalysis(body("CURRENT"), "2026-10") as MonthlyAnalysis, "SUCCESS", true);
    assert.equal(late.month, "2026-09");
    assert.equal(late.phase, "loading");
    assert.equal(late.analysis, null);
    assert.equal(late.submitting, false);
    assert.equal(applyGenerate(switched.screen, attempt, parseMonthlyAnalysis(body("CURRENT"), "2026-10") as MonthlyAnalysis, "SUCCESS", false), switched.screen);
  });

  it("waits for a manual GET after IN_PROGRESS and does not offer generate again", () => {
    const ready = readyScreen("NO_ANALYSIS", { snapshot: null, expenseTransactionCount: 1, emptyMonth: false });
    const attempt = beginGenerate(ready);
    assert.ok(attempt);
    const waiting = rejectGenerate(attempt.screen, attempt, analysisGenerateMessage(new ApiError(409, "IN_PROGRESS", "server")), true);
    assert.equal(beginGenerate(waiting), null);
    assert.equal(canRefreshAnalysis(waiting), true);
    const reload = requestAnalysis(waiting, waiting.month);
    assert.equal(reload.duringSubmit, false);
    assert.equal(reload.screen.submitting, false);
    const current = parseMonthlyAnalysis(body("CURRENT"), "2026-10");
    assert.ok(current);
    const done = settleAnalysis(reload.screen, reload.generation, current, true);
    assert.equal(done.analysis?.viewState, "CURRENT");
    assert.equal(analysisSections(done.analysis as MonthlyAnalysis).length, 4);
    assert.equal(beginGenerate(done), null);
    assert.equal(canRefreshAnalysis(done), false);
    const stillRunning = parseMonthlyAnalysis(body("GENERATION_IN_PROGRESS", {
      generation: { state: "IN_PROGRESS", active: true },
    }), "2026-10");
    assert.ok(stillRunning);
    const pending = settleAnalysis(reload.screen, reload.generation, stillRunning, true);
    assert.equal(pending.analysis?.viewState, "GENERATION_IN_PROGRESS");
    assert.equal(canRefreshAnalysis(pending), true);
    assert.equal(beginGenerate(pending), null);
  });

  it("does not generate again when the current snapshot already matches", () => {
    const current = readyScreen("CURRENT", { generatedAt: "2026-10-01T04:00:00Z" });
    assert.equal(beginGenerate(current), null);
    assert.equal(canRefreshAnalysis(current), false);
    const notice = analysisNotice(current.analysis as MonthlyAnalysis, new Date("2026-10-09T04:00:00Z"));
    assert.equal(notice.includes("时间较早"), true);
    assert.equal(notice.includes("生成分析"), false);
  });

  it("keeps one in-flight POST when the same month is loaded again", () => {
    const ready = readyScreen("STALE", { stale: true, fingerprint: "1:old" });
    const attempt = beginGenerate(ready);
    assert.ok(attempt);
    const shown = requestAnalysis(attempt.screen, attempt.month);
    assert.equal(shown.duringSubmit, true);
    assert.equal(shown.screen.submitting, true);
    assert.equal(shown.screen.submitId, attempt.submitId);
    assert.equal(beginGenerate(shown.screen), null);
    const created = parseMonthlyAnalysis(body("CURRENT"), "2026-10");
    assert.ok(created);
    const applied = applyGenerate(shown.screen, attempt, created, "SUCCESS", true);
    assert.equal(applied.submitting, false);
    assert.equal(applied.analysis?.viewState, "CURRENT");
    const staleRead = parseMonthlyAnalysis(body("NO_ANALYSIS", { snapshot: null, expenseTransactionCount: 1, emptyMonth: false }), "2026-10");
    assert.ok(staleRead);
    const ignored = settleAnalysis(applied, shown.generation, staleRead, true, true);
    assert.equal(ignored.analysis?.viewState, "CURRENT");
  });

  it("uses a different message when generation times out", () => {
    const timeout = analysisGenerateMessage(new ApiError(504, "GENERATION_FAILED", "gateway"));
    const failed = analysisGenerateMessage(new ApiError(502, "GENERATION_FAILED", "provider"));
    assert.equal(timeout, ANALYSIS_TIMEOUT);
    assert.notEqual(timeout, failed);
    assert.equal(timeout.includes("取消"), false);
    const ready = readyScreen("NO_ANALYSIS", { snapshot: null, expenseTransactionCount: 1, emptyMonth: false });
    const attempt = beginGenerate(ready);
    assert.ok(attempt);
    const waiting = rejectGenerate(attempt.screen, attempt, timeout, true);
    assert.equal(beginGenerate(waiting), null);
    assert.equal(canRefreshAnalysis(waiting), true);
  });
});

function readyScreen(viewState: AnalysisViewState, overrides: Record<string, unknown> = {}) {
  const requested = requestAnalysis(openAnalysis("2026-10"), "2026-10");
  const analysis = parseMonthlyAnalysis(body(viewState, overrides), "2026-10");
  assert.ok(analysis);
  return settleAnalysis(requested.screen, requested.generation, analysis, true);
}

function keyExpense(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    transactionType: "EXPENSE",
    categoryPath: "categoryPath" in overrides ? overrides.categoryPath : null,
    transactionName: "transactionName" in overrides ? overrides.transactionName : null,
    signedAmount: "-12.00",
    businessTime: "2026-10-02T12:00:00+08:00",
  };
}

function body(viewState: AnalysisViewState, overrides: Record<string, unknown> = {}): Record<string, unknown> {
  const expenseCount = overrides.expenseTransactionCount ?? 1;
  const incomeCount = overrides.incomeTransactionCount ?? 0;
  return {
    month: "2026-10",
    fingerprint: "1:abc",
    emptyMonth: incomeCount === 0 && expenseCount === 0,
    viewState,
    facts: {
      timeZone: "Asia/Shanghai",
      currency: "CNY",
      netIncome: overrides.netIncome ?? "0.00",
      netExpense: overrides.netExpense ?? "12.00",
      surplus: overrides.surplus ?? "-12.00",
      incomeTransactionCount: incomeCount,
      expenseTransactionCount: expenseCount,
      incomeCategories: [],
      expenseCategories: [{
        name: "购物",
        transactionCount: 1,
        netAmount: "12.00",
        children: [],
      }],
      uncategorizedIncome: { transactionCount: 0, netAmount: "0.00" },
      uncategorizedExpense: { transactionCount: 0, netAmount: "0.00" },
      keyExpenses: overrides.keyExpenses ?? [],
    },
    snapshot: overrides.snapshot === null ? undefined : {
      id: 9,
      fingerprint: overrides.fingerprint ?? "1:abc",
      stale: overrides.stale ?? false,
      monthlyOverview: overview,
      expenseStructure: "支出净额12.00元。",
      surplusReasons: "结余-12.00元。\n支出高于收入。",
      pointsToWatch: "留意购物。",
      provider: "dashscope",
      model: "qwen3.7-flash",
      promptVersion: "v1",
      generatedAt: overrides.generatedAt ?? "2026-10-10T08:00:00Z",
    },
    generation: overrides.generation,
  };
}

function memoryStorage(): KeyValueStorage {
  let value: string | undefined;
  return {
    getItem() {
      return value;
    },
    setItem(_key, next) {
      value = next;
    },
    removeItem() {
      value = undefined;
    },
  };
}

function apiFor(responder: (request: HttpTransportRequest) => { statusCode: number; data: unknown }) {
  const storage = memoryStorage();
  saveSession(storage, { token: "opaque-token", expiresAt: "2099-01-01T00:00:00Z" });
  return createMonthlyAnalysisApi(createHttpClient({
    baseUrl: "http://127.0.0.1:8080",
    storage,
    now: () => new Date("2026-10-10T04:00:00Z"),
    onUnauthorized: () => undefined,
    transport: async (request) => responder(request),
  }));
}
