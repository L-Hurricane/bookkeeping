import { ApiError } from "../http/client";
import { beijingWallClock } from "../ledger/month";
import type { MonthlyAnalysis } from "./contract";

export const ANALYSIS_LOAD_ERROR = "分析结果读取失败，请重新加载";
export const ANALYSIS_CONTRACT_ERROR = "分析结果无法识别";
export const ANALYSIS_NETWORK_ERROR = "网络异常，请稍后重试";
export const ANALYSIS_STALE = "账本数据已变化，当前展示的是此前的分析，结果可能已过期。";
export const ANALYSIS_IN_PROGRESS = "正在分析。";
export const ANALYSIS_FAILED = "分析没有完成。这不是账本数据出错。";
export const ANALYSIS_NONE = "这个月还没有分析。";
export const ANALYSIS_EMPTY_MONTH = "当前月份没有可分析的收支记录。所指是这个月的账本还没有收入或支出。";
export const ANALYSIS_EMPTY_PERIOD = "当前期间没有可分析的收支记录。所指是这个期间的账本还没有收入或支出。";
export const ANALYSIS_REFRESH = "月份数据已变化，请重新加载后再分析。";
export const ANALYSIS_QUOTA = "今日成功分析次数已用完。";
export const ANALYSIS_REUSED = "已沿用当前账本对应的分析结果。";
export const ANALYSIS_TIMEOUT = "分析请求超时了。请稍后刷新分析状态。";
export const ANALYSIS_AGE_DAYS = 7;
export const ANALYSIS_AI_CAUTION =
  "以上统计基于已记录的账本数据；AI 生成的观察与建议可能存在偏差，请结合实际交易明细核对。";
const GENERATED_AT_PATTERN = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(\.\d+)?(Z|\+00:00)$/;

export interface AnalysisSection {
  readonly title: string;
  readonly text: string;
}

export interface AnalysisFactLine {
  readonly label: string;
  readonly text: string;
}

export interface AnalysisScreen {
  readonly generation: number;
  readonly submitId: number;
  readonly submitting: boolean;
  readonly month: string;
  readonly phase: "loading" | "ready" | "error";
  readonly analysis: MonthlyAnalysis | null;
  readonly error: string;
  readonly actionMessage: string;
}

export interface AnalysisGenerateAttempt {
  readonly screen: AnalysisScreen;
  readonly submitId: number;
  readonly loadGeneration: number;
  readonly month: string;
  readonly expectedFingerprint: string;
}

export interface AnalysisLoad {
  readonly screen: AnalysisScreen;
  readonly generation: number;
  readonly duringSubmit: boolean;
}

export function openAnalysis(month: string): AnalysisScreen {
  return { generation: 0, submitId: 0, submitting: false, month, phase: "loading", analysis: null, error: "", actionMessage: "" };
}

export function requestAnalysis(screen: AnalysisScreen, month: string): AnalysisLoad {
  const generation = screen.generation + 1;
  const duringSubmit = month === screen.month && screen.submitting;
  return {
    generation,
    duringSubmit,
    screen: {
      generation,
      submitId: screen.submitId,
      submitting: duringSubmit,
      month,
      phase: "loading",
      analysis: null,
      error: "",
      actionMessage: duringSubmit ? screen.actionMessage : "",
    },
  };
}

export function settleAnalysis(screen: AnalysisScreen, generation: number, analysis: MonthlyAnalysis, pageAlive: boolean, duringSubmit = false): AnalysisScreen {
  if (!pageAlive || screen.generation !== generation || analysis.month !== screen.month) {
    return screen;
  }
  if (duringSubmit && !screen.submitting) {
    return screen;
  }
  return { ...screen, phase: "ready", analysis, error: "", actionMessage: "" };
}

export function failAnalysis(screen: AnalysisScreen, generation: number, error: string, pageAlive: boolean): AnalysisScreen {
  if (!pageAlive || screen.generation !== generation || screen.submitting) {
    return screen;
  }
  return { ...screen, phase: "error", analysis: null, error };
}

export function analysisFactLines(analysis: MonthlyAnalysis): AnalysisFactLine[] {
  const facts = analysis.facts;
  return [
    { label: "净收入", text: `${facts.netIncome} 元，${facts.incomeTransactionCount} 笔` },
    { label: "净支出", text: `${facts.netExpense} 元，${facts.expenseTransactionCount} 笔` },
    { label: "结余", text: `${facts.surplus} 元` },
  ];
}

export function canRefreshAnalysis(screen: AnalysisScreen): boolean {
  if (screen.phase !== "ready" || screen.submitting) {
    return false;
  }
  if (screen.analysis?.viewState === "GENERATION_IN_PROGRESS") {
    return true;
  }
  return screen.actionMessage === ANALYSIS_IN_PROGRESS || screen.actionMessage === ANALYSIS_TIMEOUT;
}

export function canGenerateAnalysis(screen: AnalysisScreen): boolean {
  const analysis = screen.analysis;
  return screen.phase === "ready" && !screen.submitting && !canRefreshAnalysis(screen)
      && screen.actionMessage !== ANALYSIS_REFRESH
      && analysis !== null && analysis.month === screen.month
      && analysis.viewState !== "CURRENT" && analysis.viewState !== "EMPTY_MONTH"
      && analysis.viewState !== "GENERATION_IN_PROGRESS";
}

export function beginGenerate(screen: AnalysisScreen): AnalysisGenerateAttempt | null {
  if (!canGenerateAnalysis(screen) || screen.analysis === null) {
    return null;
  }
  const submitId = screen.submitId + 1;
  const next = { ...screen, submitId, submitting: true, actionMessage: "" };
  return {
    screen: next,
    submitId,
    loadGeneration: screen.generation,
    month: screen.analysis.month,
    expectedFingerprint: screen.analysis.fingerprint,
  };
}

export function applyGenerate(screen: AnalysisScreen, attempt: AnalysisGenerateAttempt, analysis: MonthlyAnalysis, outcome: "SUCCESS" | "REUSED", pageAlive: boolean): AnalysisScreen {
  if (!sameAttempt(screen, attempt, pageAlive) || analysis.month !== attempt.month) {
    return screen;
  }
  return {
    ...screen,
    submitting: false,
    phase: "ready",
    analysis,
    error: "",
    actionMessage: outcome === "REUSED" ? ANALYSIS_REUSED : "",
  };
}

export function rejectGenerate(screen: AnalysisScreen, attempt: AnalysisGenerateAttempt, message: string, pageAlive: boolean): AnalysisScreen {
  if (!sameAttempt(screen, attempt, pageAlive)) {
    return screen;
  }
  return { ...screen, submitting: false, actionMessage: message };
}

export function analysisGenerateMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 0 || error.code === "NETWORK_ERROR") {
      return ANALYSIS_NETWORK_ERROR;
    }
    if (error.code === "IN_PROGRESS") {
      return ANALYSIS_IN_PROGRESS;
    }
    if (error.code === "REFRESH_REQUIRED") {
      return ANALYSIS_REFRESH;
    }
    if (error.code === "QUOTA_EXCEEDED") {
      return ANALYSIS_QUOTA;
    }
    if (error.code === "EMPTY_MONTH") {
      return ANALYSIS_EMPTY_MONTH;
    }
    if (error.code === "EMPTY_PERIOD") {
      return ANALYSIS_EMPTY_PERIOD;
    }
    if (error.code === "GENERATION_FAILED" && error.status === 504) {
      return ANALYSIS_TIMEOUT;
    }
    if (error.code === "GENERATION_FAILED") {
      return ANALYSIS_FAILED;
    }
  }
  if (error instanceof AnalysisResponseError) {
    return ANALYSIS_CONTRACT_ERROR;
  }
  return ANALYSIS_FAILED;
}

function sameAttempt(screen: AnalysisScreen, attempt: AnalysisGenerateAttempt, pageAlive: boolean): boolean {
  return pageAlive && screen.submitting && screen.submitId === attempt.submitId && screen.month === attempt.month;
}

export function analysisAiCaution(analysis: MonthlyAnalysis): string {
  return analysisSections(analysis).length === 0 ? "" : ANALYSIS_AI_CAUTION;
}

export function analysisSections(analysis: MonthlyAnalysis): AnalysisSection[] {
  const snapshot = analysis.snapshot;
  if (snapshot === null || analysis.viewState === "NO_ANALYSIS" || analysis.viewState === "EMPTY_MONTH") {
    return [];
  }
  return [
    { title: "本月概况", text: snapshot.monthlyOverview },
    { title: "支出结构", text: snapshot.expenseStructure },
    { title: "结余情况与可能原因", text: snapshot.surplusReasons },
    { title: "值得关注", text: snapshot.pointsToWatch },
  ];
}

export function analysisNotice(analysis: MonthlyAnalysis, now: Date): string {
  const parts: string[] = [];
  const stateNotice = viewStateNotice(analysis);
  if (stateNotice !== "") {
    parts.push(stateNotice);
  }
  if (analysis.snapshot !== null && analysis.snapshot.stale && stateNotice !== ANALYSIS_STALE) {
    parts.push(ANALYSIS_STALE);
  }
  if (analysis.snapshot !== null && !analysis.snapshot.stale) {
    const age = analysisAgeNotice(analysis.snapshot.generatedAt, now);
    if (age !== null) {
      parts.push(age);
    }
  }
  return parts.join("\n");
}

/** Shanghai calendar days. A matching snapshot older than seven days gets a notice and stays current. */
export function analysisAgeNotice(generatedAt: string, now: Date): string | null {
  const match = GENERATED_AT_PATTERN.exec(generatedAt);
  if (match === null) {
    return null;
  }
  const parsed = Date.parse(generatedAt);
  if (!Number.isFinite(parsed)) {
    return null;
  }
  const generated = beijingWallClock(new Date(parsed));
  const today = beijingWallClock(now);
  const days = utcDay(today.year, today.month, today.day) - utcDay(generated.year, generated.month, generated.day);
  if (days <= ANALYSIS_AGE_DAYS) {
    return null;
  }
  return `这份分析生成于${generated.year}年${generated.month}月${generated.day}日，时间较早，可能尚未反映后续的模型能力更新。`;
}

function utcDay(year: number, month: number, day: number): number {
  return Math.floor(Date.UTC(year, month - 1, day) / 86_400_000);
}

function viewStateNotice(analysis: MonthlyAnalysis): string {
  switch (analysis.viewState) {
    case "CURRENT":
      return "";
    case "STALE":
      return ANALYSIS_STALE;
    case "GENERATION_IN_PROGRESS":
      return ANALYSIS_IN_PROGRESS;
    case "GENERATION_FAILED":
      return ANALYSIS_FAILED;
    case "NO_ANALYSIS":
      return ANALYSIS_NONE;
    case "EMPTY_MONTH":
      return ANALYSIS_EMPTY_MONTH;
  }
}

export function analysisLoadMessage(error: unknown): string {
  if (error instanceof AnalysisResponseError) {
    return ANALYSIS_CONTRACT_ERROR;
  }
  if (error instanceof ApiError && (error.status === 0 || error.code === "NETWORK_ERROR")) {
    return ANALYSIS_NETWORK_ERROR;
  }
  return ANALYSIS_LOAD_ERROR;
}

export class AnalysisResponseError extends Error {
  constructor(message = "分析结果无法识别") {
    super(message);
    this.name = "AnalysisResponseError";
  }
}
