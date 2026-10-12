import { ApiError } from "../http/client";
import type { MonthlyAnalysis } from "./contract";

export const ANALYSIS_LOAD_ERROR = "分析结果读取失败，请重新加载";
export const ANALYSIS_CONTRACT_ERROR = "分析结果无法识别";
export const ANALYSIS_NETWORK_ERROR = "网络异常，请稍后重试";
export const ANALYSIS_STALE = "账本数据已变化，当前展示的是此前的分析，结果可能已过期。";
export const ANALYSIS_IN_PROGRESS = "正在分析。";
export const ANALYSIS_FAILED = "分析没有完成。这不是账本数据出错。";
export const ANALYSIS_NONE = "这个月还没有分析。";
export const ANALYSIS_EMPTY_MONTH = "当前月份没有可分析的收支记录。所指是这个月的账本还没有收入或支出。";

export interface AnalysisSection {
  readonly title: string;
  readonly text: string;
}

export interface AnalysisScreen {
  readonly generation: number;
  readonly month: string;
  readonly phase: "loading" | "ready" | "error";
  readonly analysis: MonthlyAnalysis | null;
  readonly error: string;
}

export function openAnalysis(month: string): AnalysisScreen {
  return { generation: 0, month, phase: "loading", analysis: null, error: "" };
}

export function requestAnalysis(screen: AnalysisScreen, month: string): { screen: AnalysisScreen; generation: number } {
  const generation = screen.generation + 1;
  return {
    generation,
    screen: { generation, month, phase: "loading", analysis: null, error: "" },
  };
}

export function settleAnalysis(screen: AnalysisScreen, generation: number, analysis: MonthlyAnalysis, pageAlive: boolean): AnalysisScreen {
  if (!pageAlive || screen.generation !== generation || analysis.month !== screen.month) {
    return screen;
  }
  return { ...screen, phase: "ready", analysis, error: "" };
}

export function failAnalysis(screen: AnalysisScreen, generation: number, error: string, pageAlive: boolean): AnalysisScreen {
  if (!pageAlive || screen.generation !== generation) {
    return screen;
  }
  return { ...screen, phase: "error", analysis: null, error };
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

export function analysisNotice(analysis: MonthlyAnalysis): string {
  const stateNotice = viewStateNotice(analysis);
  if (analysis.snapshot !== null && analysis.snapshot.stale && stateNotice !== ANALYSIS_STALE) {
    return stateNotice === "" ? ANALYSIS_STALE : `${stateNotice}\n${ANALYSIS_STALE}`;
  }
  return stateNotice;
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
