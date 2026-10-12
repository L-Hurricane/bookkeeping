const MONEY_PATTERN = /^-?[0-9]+\.[0-9]{2}$/;
const MONTH_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/;

export const ANALYSIS_VIEW_STATES = [
  "CURRENT",
  "STALE",
  "GENERATION_IN_PROGRESS",
  "GENERATION_FAILED",
  "NO_ANALYSIS",
  "EMPTY_MONTH",
] as const;

export type AnalysisViewState = (typeof ANALYSIS_VIEW_STATES)[number];
export type AnalysisGenerationState = "IN_PROGRESS" | "FAILED" | "SUCCEEDED";

export interface AnalysisCategory {
  readonly name: string;
  readonly transactionCount: number;
  readonly netAmount: string;
  readonly children: readonly AnalysisCategory[];
}

export interface AnalysisUncategorized {
  readonly transactionCount: number;
  readonly netAmount: string;
}

export interface AnalysisKeyExpense {
  readonly transactionType: string;
  readonly categoryPath: string | null;
  readonly transactionName: string | null;
  readonly signedAmount: string;
  readonly businessTime: string;
}

export interface AnalysisFacts {
  readonly timeZone: string;
  readonly currency: string;
  readonly netIncome: string;
  readonly netExpense: string;
  readonly surplus: string;
  readonly incomeTransactionCount: number;
  readonly expenseTransactionCount: number;
  readonly incomeCategories: readonly AnalysisCategory[];
  readonly expenseCategories: readonly AnalysisCategory[];
  readonly uncategorizedIncome: AnalysisUncategorized;
  readonly uncategorizedExpense: AnalysisUncategorized;
  readonly keyExpenses: readonly AnalysisKeyExpense[];
}

export interface AnalysisSnapshot {
  readonly id: number;
  readonly fingerprint: string;
  readonly stale: boolean;
  readonly monthlyOverview: string;
  readonly expenseStructure: string;
  readonly surplusReasons: string;
  readonly pointsToWatch: string;
  readonly provider: string;
  readonly model: string;
  readonly promptVersion: string;
  readonly generatedAt: string;
}

export interface AnalysisGeneration {
  readonly state: AnalysisGenerationState;
  readonly active: boolean;
}

/** One GET body. Facts and fingerprint are kept together from that response. */
export interface MonthlyAnalysis {
  readonly month: string;
  readonly fingerprint: string;
  readonly emptyMonth: boolean;
  readonly viewState: AnalysisViewState;
  readonly facts: AnalysisFacts;
  readonly snapshot: AnalysisSnapshot | null;
  readonly generation: AnalysisGeneration | null;
}

export function parseMonthlyAnalysis(value: unknown, month: string): MonthlyAnalysis | null {
  if (!isRecord(value) || value.month !== month || !isFingerprint(value.fingerprint) || typeof value.emptyMonth !== "boolean") {
    return null;
  }
  if (!isViewState(value.viewState)) {
    return null;
  }
  const facts = parseFacts(value.facts);
  const snapshot = parseSnapshot(value.snapshot);
  const generation = parseGeneration(value.generation);
  if (facts === null || snapshot === undefined || generation === undefined) {
    return null;
  }
  const empty = facts.incomeTransactionCount === 0 && facts.expenseTransactionCount === 0;
  if (value.emptyMonth !== empty) {
    return null;
  }
  if (!snapshotMatches(value.viewState, snapshot)) {
    return null;
  }
  if (value.viewState === "GENERATION_IN_PROGRESS" && (generation === null || !generation.active)) {
    return null;
  }
  if (value.viewState === "EMPTY_MONTH" && !empty) {
    return null;
  }
  return {
    month,
    fingerprint: value.fingerprint,
    emptyMonth: value.emptyMonth,
    viewState: value.viewState,
    facts,
    snapshot,
    generation,
  };
}

function snapshotMatches(viewState: AnalysisViewState, snapshot: AnalysisSnapshot | null): boolean {
  if (viewState === "CURRENT") {
    return snapshot !== null && !snapshot.stale;
  }
  if (viewState === "STALE") {
    return snapshot !== null && snapshot.stale;
  }
  if (viewState === "NO_ANALYSIS" || viewState === "EMPTY_MONTH") {
    return snapshot === null;
  }
  return true;
}

function parseFacts(value: unknown): AnalysisFacts | null {
  if (!isRecord(value) || value.timeZone !== "Asia/Shanghai" || value.currency !== "CNY") {
    return null;
  }
  if (!isMoney(value.netIncome) || !isMoney(value.netExpense) || !isMoney(value.surplus)) {
    return null;
  }
  if (!isCount(value.incomeTransactionCount) || !isCount(value.expenseTransactionCount)) {
    return null;
  }
  const incomeCategories = parseCategories(value.incomeCategories);
  const expenseCategories = parseCategories(value.expenseCategories);
  const uncategorizedIncome = parseUncategorized(value.uncategorizedIncome);
  const uncategorizedExpense = parseUncategorized(value.uncategorizedExpense);
  const keyExpenses = parseKeyExpenses(value.keyExpenses);
  if (incomeCategories === null || expenseCategories === null || uncategorizedIncome === null
      || uncategorizedExpense === null || keyExpenses === null) {
    return null;
  }
  return {
    timeZone: value.timeZone,
    currency: value.currency,
    netIncome: value.netIncome,
    netExpense: value.netExpense,
    surplus: value.surplus,
    incomeTransactionCount: value.incomeTransactionCount,
    expenseTransactionCount: value.expenseTransactionCount,
    incomeCategories,
    expenseCategories,
    uncategorizedIncome,
    uncategorizedExpense,
    keyExpenses,
  };
}

function parseCategories(value: unknown): AnalysisCategory[] | null {
  if (!Array.isArray(value)) {
    return null;
  }
  const categories: AnalysisCategory[] = [];
  for (const item of value) {
    const category = parseCategory(item);
    if (category === null) {
      return null;
    }
    categories.push(category);
  }
  return categories;
}

function parseCategory(value: unknown): AnalysisCategory | null {
  if (!isRecord(value) || !isText(value.name) || !isCount(value.transactionCount) || !isMoney(value.netAmount)) {
    return null;
  }
  const children = parseCategories(value.children);
  if (children === null) {
    return null;
  }
  return { name: value.name, transactionCount: value.transactionCount, netAmount: value.netAmount, children };
}

function parseUncategorized(value: unknown): AnalysisUncategorized | null {
  if (!isRecord(value) || !isCount(value.transactionCount) || !isMoney(value.netAmount)) {
    return null;
  }
  return { transactionCount: value.transactionCount, netAmount: value.netAmount };
}

function parseKeyExpenses(value: unknown): AnalysisKeyExpense[] | null {
  if (!Array.isArray(value)) {
    return null;
  }
  const expenses: AnalysisKeyExpense[] = [];
  for (const item of value) {
    if (!isRecord(item)) {
      return null;
    }
    const categoryPath = parseNullableText(item.categoryPath);
    const transactionName = parseNullableText(item.transactionName);
    if (!isText(item.transactionType) || categoryPath === undefined || transactionName === undefined
        || !isMoney(item.signedAmount) || !isText(item.businessTime)) {
      return null;
    }
    expenses.push({
      transactionType: item.transactionType,
      categoryPath,
      transactionName,
      signedAmount: item.signedAmount,
      businessTime: item.businessTime,
    });
  }
  return expenses;
}

function parseSnapshot(value: unknown): AnalysisSnapshot | null | undefined {
  if (value === undefined || value === null) {
    return null;
  }
  if (!isRecord(value) || !isPositiveId(value.id) || !isFingerprint(value.fingerprint) || typeof value.stale !== "boolean") {
    return undefined;
  }
  if (!isText(value.monthlyOverview) || !isText(value.expenseStructure) || !isText(value.surplusReasons) || !isText(value.pointsToWatch)) {
    return undefined;
  }
  if (!isText(value.provider) || !isText(value.model) || !isText(value.promptVersion) || !isText(value.generatedAt)) {
    return undefined;
  }
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
  if (value === undefined || value === null) {
    return null;
  }
  if (!isRecord(value) || typeof value.active !== "boolean") {
    return undefined;
  }
  if (value.state !== "IN_PROGRESS" && value.state !== "FAILED" && value.state !== "SUCCEEDED") {
    return undefined;
  }
  return { state: value.state, active: value.active };
}

function isViewState(value: unknown): value is AnalysisViewState {
  return typeof value === "string" && ANALYSIS_VIEW_STATES.some((state) => state === value);
}

function isFingerprint(value: unknown): value is string {
  return typeof value === "string" && value.length > 0 && value.length <= 128 && !/\s/.test(value);
}

/** JSON null and an omitted field both stay null. Jackson non_null omits these strings. */
function parseNullableText(value: unknown): string | null | undefined {
  if (value === undefined || value === null) {
    return null;
  }
  return isText(value) ? value : undefined;
}

function isText(value: unknown): value is string {
  return typeof value === "string" && value.length > 0;
}

function isMoney(value: unknown): value is string {
  return typeof value === "string" && MONEY_PATTERN.test(value);
}

function isCount(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}

function isPositiveId(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

export interface GenerateMonthlyAnalysisResult {
  readonly outcome: "SUCCESS" | "REUSED";
  readonly analysis: MonthlyAnalysis;
}

export function parseGenerateResult(value: unknown, month: string): GenerateMonthlyAnalysisResult | null {
  if (!isRecord(value) || (value.outcome !== "SUCCESS" && value.outcome !== "REUSED")) {
    return null;
  }
  const analysis = parseMonthlyAnalysis(value.analysis, month);
  if (analysis === null) {
    return null;
  }
  return { outcome: value.outcome, analysis };
}

export function isAnalysisMonth(value: string): boolean {
  return MONTH_PATTERN.test(value);
}
