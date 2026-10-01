export interface DetailScrollAnchor {
  readonly scrollTop: number;
  readonly context: string;
}

export interface DetailReturn {
  readonly dirty: boolean;
  readonly context: string;
  readonly scrollTop: number;
}

export function detailReturnPlan(
  visit: DetailReturn | null,
  currentContext: string,
  explicitLocate: boolean,
): { readonly refresh: "keep" | "background-refresh" | "reload"; readonly scrollTop: number | null } {
  if (visit === null) {
    return { refresh: "reload", scrollTop: null };
  }
  if (!visit.dirty) {
    return { refresh: "keep", scrollTop: null };
  }
  if (visit.context !== currentContext) {
    return { refresh: "reload", scrollTop: null };
  }
  return {
    refresh: "background-refresh",
    scrollTop: restoreDetailScroll(
      { scrollTop: visit.scrollTop, context: visit.context },
      currentContext,
      explicitLocate,
    ),
  };
}

export interface OpenList {
  readonly month: string;
  readonly locateMonth: string | null;
  readonly visit: DetailReturn | null;
}

export function openTransactionDetail(state: OpenList, scrollTop: number): OpenList {
  return {
    ...state,
    visit: { dirty: false, context: state.month, scrollTop },
  };
}

export function noteListStale(state: OpenList): OpenList {
  if (state.visit === null) {
    return state;
  }
  return { ...state, visit: { ...state.visit, dirty: true } };
}

export function noteSavedTransaction(state: OpenList, targetMonth: string | null): OpenList {
  const stale = noteListStale(state);
  if (targetMonth === null) {
    return stale;
  }
  return { ...stale, month: targetMonth, locateMonth: targetMonth };
}

export type ListShow =
  | { readonly refresh: "keep" }
  | { readonly refresh: "background-refresh"; readonly scrollTop: number | null; readonly locate: boolean }
  | { readonly refresh: "reload"; readonly scope: "ledger" | "account"; readonly locate: boolean };

export function showOpenList(state: OpenList): { readonly state: OpenList; readonly show: ListShow } {
  const hadVisit = state.visit !== null;
  const plan = detailReturnPlan(state.visit, state.month, state.locateMonth !== null);
  const next = { ...state, visit: null };
  const locate = state.locateMonth !== null;
  if (plan.refresh === "keep") {
    return { state: next, show: { refresh: "keep" } };
  }
  if (plan.refresh === "background-refresh") {
    return { state: next, show: { refresh: "background-refresh", scrollTop: plan.scrollTop, locate } };
  }
  return { state: next, show: { refresh: "reload", scope: hadVisit ? "ledger" : "account", locate } };
}

export function restoreDetailScroll(
  saved: DetailScrollAnchor | null,
  context: string,
  explicitLocate: boolean,
): number | null {
  if (saved === null || explicitLocate || saved.context !== context) {
    return null;
  }
  if (!Number.isFinite(saved.scrollTop) || saved.scrollTop < 0) {
    return null;
  }
  return saved.scrollTop;
}
