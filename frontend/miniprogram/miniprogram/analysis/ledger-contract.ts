import { ANALYSIS_VIEW_STATES, type AnalysisGeneration, type AnalysisSnapshot, type AnalysisViewState } from "./contract";

const MONEY_PATTERN = /^-?[0-9]+\.[0-9]{2}$/;
const MONTH_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/;
const YEAR_PATTERN = /^\d{4}$/;

export interface LedgerAnalysis {
  readonly periodKind: "MONTH" | "YEAR";
  readonly period: string;
  readonly fingerprint: string;
  readonly emptyPeriod: boolean;
  readonly viewState: AnalysisViewState;
  readonly opening: string;
  readonly closing: string;
  readonly change: string;
  readonly incomeNet: string;
  readonly expenseNet: string;
  readonly incomeCount: number;
  readonly expenseCount: number;
  readonly priorPeriod: string;
  readonly priorChange: string;
  readonly priorIncomeNet: string;
  readonly priorExpenseNet: string;
  readonly snapshot: AnalysisSnapshot | null;
  readonly generation: AnalysisGeneration | null;
}

export function parseLedgerAnalysis(value: unknown, periodKind: "MONTH" | "YEAR", period: string): LedgerAnalysis | null {
  if (!isRecord(value) || value.periodKind !== periodKind || value.period !== period) return null;
  if (typeof value.fingerprint !== "string" || value.fingerprint.length === 0) return null;
  if (typeof value.emptyPeriod !== "boolean" || !isViewState(value.viewState)) return null;
  if (!isMoney(value.opening) || !isMoney(value.closing) || !isMoney(value.change)) return null;
  if (!isMoney(value.incomeNet) || !isMoney(value.expenseNet)) return null;
  if (!isCount(value.incomeCount) || !isCount(value.expenseCount)) return null;
  if (typeof value.priorPeriod !== "string" || !isMoney(value.priorChange)) return null;
  if (!isMoney(value.priorIncomeNet) || !isMoney(value.priorExpenseNet)) return null;
  const snapshot = value.snapshot === null ? null : parseSnapshot(value.snapshot);
  const generation = value.generation === null || value.generation === undefined ? null : parseGeneration(value.generation);
  if (snapshot === undefined || generation === undefined) return null;
  return {
    periodKind,
    period,
    fingerprint: value.fingerprint,
    emptyPeriod: value.emptyPeriod,
    viewState: value.viewState,
    opening: value.opening,
    closing: value.closing,
    change: value.change,
    incomeNet: value.incomeNet,
    expenseNet: value.expenseNet,
    incomeCount: value.incomeCount,
    expenseCount: value.expenseCount,
    priorPeriod: value.priorPeriod,
    priorChange: value.priorChange,
    priorIncomeNet: value.priorIncomeNet,
    priorExpenseNet: value.priorExpenseNet,
    snapshot,
    generation,
  };
}

export function isLedgerPeriod(periodKind: string, period: string): boolean {
  return periodKind === "MONTH" && MONTH_PATTERN.test(period) || periodKind === "YEAR" && YEAR_PATTERN.test(period);
}

function parseSnapshot(value: unknown): AnalysisSnapshot | null | undefined {
  if (!isRecord(value) || typeof value.id !== "number" || !Number.isSafeInteger(value.id)) return undefined;
  if (typeof value.fingerprint !== "string" || typeof value.stale !== "boolean") return undefined;
  if (typeof value.monthlyOverview !== "string" || typeof value.expenseStructure !== "string") return undefined;
  if (typeof value.surplusReasons !== "string" || typeof value.pointsToWatch !== "string") return undefined;
  if (typeof value.provider !== "string" || typeof value.model !== "string" || typeof value.promptVersion !== "string") return undefined;
  if (typeof value.generatedAt !== "string") return undefined;
  return {
    id: value.id,
    fingerprint: value.fingerprint,
    stale: value.stale,
    monthlyOverview: value.monthlyOverview,
    expenseStructure: value.expenseStructure,
    surplusReasons: value.surplusReasons,
    pointsToWatch: value.pointsToWatch,
    provider: value.provider,
    model: value.model,
    promptVersion: value.promptVersion,
    generatedAt: value.generatedAt,
  };
}

function parseGeneration(value: unknown): AnalysisGeneration | null | undefined {
  if (!isRecord(value) || typeof value.active !== "boolean") return undefined;
  if (value.state !== "IN_PROGRESS" && value.state !== "FAILED" && value.state !== "SUCCEEDED") return undefined;
  return { state: value.state, active: value.active };
}

function isViewState(value: unknown): value is AnalysisViewState {
  return typeof value === "string" && ANALYSIS_VIEW_STATES.some((state) => state === value);
}

function isMoney(value: unknown): value is string {
  return typeof value === "string" && MONEY_PATTERN.test(value);
}

function isCount(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}
