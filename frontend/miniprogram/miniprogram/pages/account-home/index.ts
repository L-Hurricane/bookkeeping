import { ARCHIVED_HISTORY_HINT } from "../../account/archive-copy";
import {
  formatCurrentBalance,
  shouldWarnNegativeBalance,
  type Account,
} from "../../account/account";
import { stashLedgerHandoff, takeLedgerHandoff } from "../../account/ledger-handoff";
import { logoutFailureView, resumeAfterLogoutFailure, selectedMonthDuringLogout } from "../../account/logout-view";
import { beginLoad, beginLogout, canApplyLoadResult, createLoadGuard, endLogout } from "../../account/load-guard";
import { moneyToneClass } from "../../account/money-tone";
import { accountHomePath, accountListPath, accountSettingsPath, parseAccountHomeQuery } from "../../account/navigation";
import { canApplyLedgerResult, formatMoneyLabel, ledgerRows } from "../../ledger/ledger";
import { currentBeijingMonth, formatMonthLabel, nextMonth, previousMonth } from "../../ledger/month";
import {
  acceptAdjustmentCreated,
  acceptTransferCreated,
  acceptTransactionCreated,
  applyAcceptedLocate,
  canRunLocate,
  centeredScrollTop,
  changeSelectedMonth,
  clearHighlight,
  finishHighlight,
  HIGHLIGHT_MS,
  idleHighlight,
  nextLocateGeneration,
  parsePostSaveResult,
  settleLedger,
  startHighlight,
  type LedgerLocateIntent,
  type LocatedTransaction,
} from "../../ledger/post-save";
import { accountApi, authApi, authNavigation, ledgerApi, transactionApi } from "../../services/runtime";
import { TransactionSearchResponseError } from "../../services/transaction";
import {
  canApplySearchResult,
  createSearchDebounce,
  invalidateSearchOnInputChange,
  isActiveSearchKeyword,
  nextSearchGeneration,
  SEARCH_CONTRACT_ERROR,
  SEARCH_DEBOUNCE_MS,
  SEARCH_LOAD_ERROR,
  searchLedgerRows,
  buildLedgerLoadSetDataPatch,
  shouldRefreshSearchOnListShow,
  shouldRunDebouncedSearch,
  trimSearchKeyword,
} from "../../transaction/transaction-search";
import { validateBusinessTime } from "../../transaction/business-time";
import { adjustmentCreatePath, adjustmentDetailPath, canOpenAdjustmentDetail, canOpenBalanceAdjustment } from "../../balance-adjustment/navigation";
import {
  canOpenTransfer,
  canOpenTransferDetail,
  transferCreatePath,
  transferDetailPath,
} from "../../transfer/navigation";
import { statisticsPagePath } from "../../statistics/drilldown";
import { canOpenStatistics } from "../../statistics/navigation";
import { canOpenCreateTransaction, canOpenTransactionDetail, transactionCreatePath, transactionDetailPath } from "../../transaction/navigation";
import { isUnauthorized, LedgerResponseError } from "../../services/ledger";
import { noteListStale, noteSavedTransaction, showOpenList, type DetailReturn, type OpenList } from "../../navigation/scroll-restore";

Page({
  loadGuard: createLoadGuard(),
  searchDebounce: createSearchDebounce(
    SEARCH_DEBOUNCE_MS,
    (callback, delayMs) => setTimeout(callback, delayMs) as unknown as number,
    clearTimeout,
  ),
  searchGeneration: 0,
  searchRequestGeneration: 0,
  searchKeyword: "",
  selectedMonth: "",
  accountId: 0,
  accountCreatedTime: "",
  currency: "",
  accountKind: "NORMAL" as Account["kind"],
  archived: false,
  contextReady: false,
  pendingPostSave: null as LedgerLocateIntent | null,
  highlight: idleHighlight(),
  highlightTimer: null as number | null,
  locateGeneration: 0,
  pageScrollTop: 0,
  detailReturn: null as DetailReturn | null,
  scrollRestoreGeneration: 0,

  data: {
    phase: "account-loading" as "account-loading" | "account-error" | "ledger-loading" | "ready" | "ledger-error",
    name: "",
    kindLabel: "",
    balanceLabel: "",
    balanceToneClass: "money-zero",
    negativeBalanceHint: "",
    archived: false,
    archiveHint: "",
    monthLabel: "",
    incomeLabel: "",
    incomeToneClass: "money-zero",
    expenseLabel: "",
    expenseToneClass: "money-zero",
    surplusLabel: "",
    surplusToneClass: "money-zero",
    countLabel: "",
    rows: [] as ReturnType<typeof ledgerRows>,
    empty: false,
    searchInput: "",
    searchLoading: false,
    searchEmpty: false,
    searchErrorMessage: "",
    errorMessage: "",
    loggingOut: false,
    logoutErrorMessage: "",
    highlightKey: "",
    locateMiss: "",
  },

  onLoad(query: Record<string, string | undefined>) {
    const context = parseAccountHomeQuery(query);
    if (context === null) {
      wx.reLaunch({ url: accountListPath() });
      return;
    }
    this.accountId = context.accountId;
    this.contextReady = true;
    const handoff = takeLedgerHandoff(this.accountId);
    if (handoff !== null) {
      this.pendingPostSave = handoff;
      this.selectedMonth = handoff.targetMonth;
    }
  },

  onShow() {
    if (!this.contextReady) {
      return;
    }
    if (this.selectedMonth.length === 0) {
      this.selectedMonth = currentBeijingMonth(new Date());
    }
    const shown = showOpenList(this.openList());
    this.detailReturn = shown.state.visit;
    const refreshSearch = shouldRefreshSearchOnListShow(shown.show, this.searchKeyword);
    if (shown.show.refresh === "keep") {
      return;
    }
    if (shown.show.refresh === "background-refresh") {
      void this.loadLedger("background", shown.show.scrollTop);
      if (refreshSearch) {
        void this.runSearch(this.searchKeyword);
      }
      return;
    }
    if (shown.show.scope === "ledger") {
      void this.loadLedger("replace");
      if (refreshSearch) {
        void this.runSearch(this.searchKeyword);
      }
      return;
    }
    void this.loadAccount();
  },

  onPageScroll(event: WechatMiniprogram.Page.IPageScrollOption) {
    if (Number.isFinite(event.scrollTop) && event.scrollTop >= 0) {
      this.pageScrollTop = event.scrollTop;
    }
  },

  onUnload() {
    this.loadGuard.pageAlive = false;
    this.searchDebounce.cancel();
    this.stopLocateFeedback();
  },

  async loadAccount() {
    const generation = beginLoad(this.loadGuard);
    if (generation === null) {
      return;
    }
    this.setData({ phase: "account-loading", errorMessage: "" });
    try {
      const account = await accountApi.loadAccount(this.accountId);
      if (!canApplyLoadResult(this.loadGuard, generation)) {
        return;
      }
      if (account === null) {
        this.setData({ phase: "account-error", errorMessage: "账户不存在或无权访问" });
        return;
      }
      this.resetSearchState();
      this.accountCreatedTime = account.createdTime;
      this.currency = account.currency;
      this.accountKind = account.kind;
      this.archived = account.lifecycle === "ARCHIVED";
      this.showAccount(account);
      void this.loadLedger();
    } catch (error) {
      if (!canApplyLoadResult(this.loadGuard, generation) || isUnauthorized(error)) {
        return;
      }
      this.setData({ phase: "account-error", errorMessage: "账户读取失败，请稍后重试" });
    }
  },

  showAccount(account: Account) {
    this.setData({
      name: account.name,
      kindLabel: account.kind === "CREDIT" ? "信用账户" : "普通账户",
      balanceLabel: formatCurrentBalance(account),
      balanceToneClass: moneyToneClass(account.currentBalance),
      negativeBalanceHint: this.archived || !shouldWarnNegativeBalance(account) ? "" : "余额为负，仍可继续记账",
      archived: this.archived,
      archiveHint: this.archived ? ARCHIVED_HISTORY_HINT : "",
      monthLabel: formatMonthLabel(this.selectedMonth),
      errorMessage: "",
    });
  },

  async loadLedger(presentation: "replace" | "background" = "replace", restoreScrollTop: number | null = null) {
    const month = this.selectedMonth;
    const accountId = this.accountId;
    const background = presentation === "background" && this.data.phase === "ready";
    const scrollRestoreGeneration = ++this.scrollRestoreGeneration;
    const generation = beginLoad(this.loadGuard);
    if (generation === null) {
      return;
    }
    if (!background) {
      this.setData({ phase: "ledger-loading", monthLabel: formatMonthLabel(month), errorMessage: "" });
    }
    try {
      const ledger = await ledgerApi.getLedger(accountId, month);
      if (!canApplyLedgerResult(this.loadGuard, generation, month, this.selectedMonth)) {
        return;
      }
      const settlement = settleLedger(this.pendingPostSave, "success", ledger.items);
      this.pendingPostSave = settlement.pending;
      const locate = settlement.locate;
      const explicitLocate = this.pendingPostSave !== null || locate !== null;
      const restoreTop = background && !explicitLocate ? restoreScrollTop : null;
      const ledgerView = buildLedgerLoadSetDataPatch(this.searchKeyword, ledger.items, {
        phase: "ready",
        balanceLabel: this.currency === "CNY" ? formatMoneyLabel(ledger.currentBalance) : `${ledger.currentBalance} ${this.currency}`,
        balanceToneClass: moneyToneClass(ledger.currentBalance),
        negativeBalanceHint:
          !this.archived && this.accountKind === "NORMAL" && moneyToneClass(ledger.currentBalance) === "money-negative"
            ? "余额为负，仍可继续记账"
            : "",
        monthLabel: formatMonthLabel(ledger.month),
        incomeLabel: formatMoneyLabel(ledger.summary.netIncome),
        incomeToneClass: moneyToneClass(ledger.summary.netIncome),
        expenseLabel: formatMoneyLabel(ledger.summary.netExpense),
        expenseToneClass: moneyToneClass(ledger.summary.netExpense),
        surplusLabel: formatMoneyLabel(ledger.summary.surplus),
        surplusToneClass: moneyToneClass(ledger.summary.surplus),
        countLabel: `${ledger.summary.transactionCount} 笔收支`,
        errorMessage: "",
        locateMiss: settlement.missMessage,
        highlightKey: locate === null ? this.highlight.key : locate.key,
      });
      if (locate === null) {
        this.setData(ledgerView, () => {
          if (scrollRestoreGeneration !== this.scrollRestoreGeneration || this.selectedMonth !== month) {
            return;
          }
          this.restoreListScroll(restoreTop);
        });
        return;
      }
      this.highlight = startHighlight(this.highlight, locate.key);
      this.locateGeneration = nextLocateGeneration(this.locateGeneration);
      const captured = this.locateGeneration;
      this.setData(ledgerView, () => {
        if (!canRunLocate(this.loadGuard.pageAlive, captured, this.locateGeneration)) {
          return;
        }
        this.queueScroll(locate, captured);
        this.armHighlightTimer(this.highlight.generation);
      });
    } catch (error) {
      if (!canApplyLedgerResult(this.loadGuard, generation, month, this.selectedMonth) || isUnauthorized(error)) {
        return;
      }
      if (background && this.data.phase === "ready") {
        return;
      }
      const errorMessage = error instanceof LedgerResponseError ? "流水数据不符合约定" : "流水读取失败，请稍后重试";
      this.setData({ phase: "ledger-error", errorMessage });
    }
  },

  onCreate() {
    if (this.archived || !canOpenCreateTransaction(this.data.phase, this.loadGuard.loggingOut) || !validateBusinessTime(this.accountCreatedTime)) {
      return;
    }
    wx.navigateTo({
      url: transactionCreatePath(this.accountId, this.accountCreatedTime),
      events: {
        transactionCreated: (result: unknown) => {
          this.receiveTransactionCreated(result);
        },
      },
    });
  },

  onStatistics() {
    if (!canOpenStatistics(this.data.phase, this.loadGuard.loggingOut) || this.selectedMonth.length === 0 || !validateBusinessTime(this.accountCreatedTime)) {
      return;
    }
    wx.navigateTo({
      url: statisticsPagePath(this.accountId, this.selectedMonth, this.accountCreatedTime),
    });
  },

  onAdjust() {
    if (this.archived || !canOpenBalanceAdjustment(this.data.phase, this.loadGuard.loggingOut) || !validateBusinessTime(this.accountCreatedTime)) {
      return;
    }
    wx.navigateTo({
      url: adjustmentCreatePath(this.accountId, this.accountCreatedTime),
      events: {
        balanceAdjustmentCreated: (result: unknown) => {
          this.receiveAdjustmentCreated(result);
        },
      },
    });
  },

  onTransfer() {
    if (this.archived || !canOpenTransfer(this.data.phase, this.loadGuard.loggingOut) || !validateBusinessTime(this.accountCreatedTime)) {
      return;
    }
    wx.navigateTo({
      url: transferCreatePath(this.accountId, this.accountCreatedTime),
      events: {
        transferCreated: (result: unknown) => {
          this.receiveTransferCreated(result);
        },
      },
    });
  },

  onOpenRecord(event: WechatMiniprogram.BaseEvent) {
    const kind = event.currentTarget.dataset["kind"];
    const recordId = Number(event.currentTarget.dataset["id"]);
    if (typeof kind === "string" && canOpenTransferDetail(kind, this.loadGuard.loggingOut)) {
      if (!Number.isSafeInteger(recordId) || recordId <= 0 || !validateBusinessTime(this.accountCreatedTime)) {
        return;
      }
      wx.navigateTo({
        url: transferDetailPath(this.accountId, recordId, this.accountCreatedTime),
        events: {
          transferUpdated: (result: unknown) => {
            this.receiveTransferCreated(result);
          },
          listStale: () => {
            this.detailReturn = noteListStale(this.openList()).visit;
          },
        },
      });
      return;
    }
    if (typeof kind === "string" && canOpenAdjustmentDetail(kind, this.loadGuard.loggingOut)) {
      if (!Number.isSafeInteger(recordId) || recordId <= 0) {
        return;
      }
      wx.navigateTo({ url: adjustmentDetailPath(this.accountId, recordId) });
      return;
    }
    if (typeof kind !== "string" || !canOpenTransactionDetail(kind, this.loadGuard.loggingOut)) {
      return;
    }
    if (!Number.isSafeInteger(recordId) || recordId <= 0 || !validateBusinessTime(this.accountCreatedTime)) {
      return;
    }
    this.detailReturn = { dirty: false, context: this.selectedMonth, scrollTop: this.pageScrollTop };
    wx.navigateTo({
      url: transactionDetailPath(this.accountId, recordId, this.accountCreatedTime, this.selectedMonth),
      events: {
        transactionUpdated: (result: unknown) => {
          this.captureTransactionUpdate(result);
        },
        listStale: () => {
          this.detailReturn = noteListStale(this.openList()).visit;
        },
      },
    });
  },

  openList(): OpenList {
    return {
      month: this.selectedMonth,
      locateMonth: this.pendingPostSave === null ? null : this.pendingPostSave.targetMonth,
      visit: this.detailReturn,
    };
  },

  captureTransactionUpdate(result: unknown) {
    if (this.redirectIfForeignAccount(result)) {
      return;
    }
    const current = this.locateNavigation();
    const next = acceptTransactionCreated(current, result);
    const accepted = applyAcceptedLocate(current, next);
    const pending = accepted?.pending ?? null;
    const listed = noteSavedTransaction(this.openList(), pending?.targetMonth ?? null);
    this.detailReturn = listed.visit;
    this.selectedMonth = listed.month;
    if (pending === null) {
      return;
    }
    this.pendingPostSave = pending;
    this.stopLocateFeedback();
    this.setData({ highlightKey: "", locateMiss: "" });
  },

  receiveAdjustmentCreated(payload: unknown) {
    const current = this.locateNavigation();
    this.receiveLocate(current, acceptAdjustmentCreated(current, payload));
  },

  receiveTransferCreated(payload: unknown) {
    const current = this.locateNavigation();
    this.receiveLocate(current, acceptTransferCreated(current, payload));
  },

  receiveTransactionCreated(payload: unknown) {
    if (this.redirectIfForeignAccount(payload)) {
      return;
    }
    const current = this.locateNavigation();
    this.receiveLocate(current, acceptTransactionCreated(current, payload));
  },

  redirectIfForeignAccount(payload: unknown): boolean {
    const saved = parsePostSaveResult(payload);
    if (saved === null || saved.accountId <= 0 || saved.accountId === this.accountId) {
      return false;
    }
    stashLedgerHandoff(saved.accountId, saved);
    wx.reLaunch({ url: accountHomePath(saved.accountId) });
    return true;
  },

  onOpenSettings() {
    if (this.loadGuard.loggingOut || this.accountId <= 0) {
      return;
    }
    wx.navigateTo({ url: accountSettingsPath(this.accountId) });
  },

  onBackToList() {
    if (this.loadGuard.loggingOut) {
      return;
    }
    wx.navigateBack({
      fail: () => {
        wx.reLaunch({ url: accountListPath() });
      },
    });
  },

  receiveLocate(current: ReturnType<typeof this.locateNavigation>, next: ReturnType<typeof acceptTransactionCreated>) {
    if (applyAcceptedLocate(current, next) === null) {
      return;
    }
    this.selectedMonth = next.selectedMonth;
    this.pendingPostSave = next.pending;
    this.stopLocateFeedback();
    this.setData({ highlightKey: "", locateMiss: "" });
  },

  onSearchInput(event: WechatMiniprogram.Input) {
    const searchInput = typeof event.detail.value === "string" ? event.detail.value : "";
    const invalidation = invalidateSearchOnInputChange(
      this.searchKeyword,
      searchInput,
      this.data.searchLoading,
      this.searchGeneration,
    );
    this.searchGeneration = invalidation.nextGeneration;
    const patch: Partial<typeof this.data> = { searchInput };
    if (invalidation.clearSearchLoading) {
      patch.searchLoading = false;
    }
    this.setData(patch);
    this.searchDebounce.schedule(searchInput, (keyword: string) => {
      this.applySearchKeyword(keyword);
    });
  },

  applySearchKeyword(raw: string) {
    const keyword = trimSearchKeyword(raw);
    if (!isActiveSearchKeyword(keyword)) {
      if (!isActiveSearchKeyword(this.searchKeyword)) {
        return;
      }
      this.searchKeyword = "";
      this.searchGeneration = nextSearchGeneration(this.searchGeneration);
      this.searchRequestGeneration = this.searchGeneration;
      this.setData({ searchEmpty: false, searchErrorMessage: "", searchLoading: false });
      void this.loadLedger();
      return;
    }
    if (
      !shouldRunDebouncedSearch(
        this.searchKeyword,
        keyword,
        this.data.searchLoading,
        this.searchGeneration,
        this.searchRequestGeneration,
      )
    ) {
      return;
    }
    this.searchKeyword = keyword;
    void this.runSearch(keyword);
  },

  async runSearch(keyword: string) {
    const accountId = this.accountId;
    const generation = nextSearchGeneration(this.searchGeneration);
    this.searchGeneration = generation;
    this.searchRequestGeneration = generation;
    this.setData({ searchLoading: true, searchErrorMessage: "", searchEmpty: false, phase: "ready" });
    try {
      const result = await transactionApi.searchTransactions(accountId, keyword, this.accountCreatedTime);
      if (
        !canApplySearchResult(
          this.loadGuard,
          generation,
          accountId,
          this.accountId,
          keyword,
          this.data.searchInput,
          this.searchGeneration,
          this.searchKeyword,
        )
      ) {
        return;
      }
      const rows = searchLedgerRows(result.items);
      this.setData({
        searchLoading: false,
        rows,
        searchEmpty: rows.length === 0,
        empty: false,
        searchErrorMessage: "",
        highlightKey: "",
        locateMiss: "",
      });
    } catch (error) {
      if (
        !canApplySearchResult(
          this.loadGuard,
          generation,
          accountId,
          this.accountId,
          keyword,
          this.data.searchInput,
          this.searchGeneration,
          this.searchKeyword,
        ) ||
        isUnauthorized(error)
      ) {
        return;
      }
      const searchErrorMessage = error instanceof TransactionSearchResponseError ? SEARCH_CONTRACT_ERROR : SEARCH_LOAD_ERROR;
      this.setData({ searchLoading: false, searchErrorMessage });
    }
  },

  resetSearchState() {
    this.searchDebounce.cancel();
    this.searchKeyword = "";
    this.searchGeneration = nextSearchGeneration(this.searchGeneration);
    this.searchRequestGeneration = this.searchGeneration;
    this.setData({ searchInput: "", searchLoading: false, searchEmpty: false, searchErrorMessage: "" });
  },

  onPreviousMonth() {
    this.changeMonth(previousMonth(this.selectedMonth));
  },

  onNextMonth() {
    this.changeMonth(nextMonth(this.selectedMonth));
  },

  changeMonth(month: string) {
    const selectedMonth = selectedMonthDuringLogout(this.loadGuard.loggingOut, this.selectedMonth, month);
    const next = changeSelectedMonth(this.locateNavigation(), selectedMonth);
    if (next.selectedMonth === this.selectedMonth) {
      return;
    }
    this.selectedMonth = next.selectedMonth;
    this.pendingPostSave = next.pending;
    this.detailReturn = null;
    this.pageScrollTop = 0;
    this.scrollRestoreGeneration += 1;
    this.stopLocateFeedback();
    this.setData({ highlightKey: "", locateMiss: "" });
    void this.loadLedger();
  },

  onRetry() {
    if (this.data.searchErrorMessage.length > 0 && isActiveSearchKeyword(this.searchKeyword)) {
      void this.runSearch(this.searchKeyword);
      return;
    }
    if (this.data.phase === "ledger-error") {
      void this.loadLedger();
      return;
    }
    void this.loadAccount();
  },

  async onLogout() {
    const phaseBeforeLogout = this.data.phase;
    if (!beginLogout(this.loadGuard)) {
      return;
    }
    this.setData({ loggingOut: true, logoutErrorMessage: "" });
    try {
      await authApi.logout();
      authNavigation.enterLogin();
    } catch {
      endLogout(this.loadGuard);
      const resume = resumeAfterLogoutFailure(phaseBeforeLogout);
      this.setData(logoutFailureView(this.data));
      if (resume === "account") {
        void this.loadAccount();
      } else if (resume === "ledger") {
        void this.loadLedger();
      }
    }
  },

  restoreListScroll(scrollTop: number | null) {
    if (scrollTop === null || !this.loadGuard.pageAlive) {
      return;
    }
    this.pageScrollTop = scrollTop;
    wx.pageScrollTo({ scrollTop, duration: 0 });
  },

  locateNavigation() {
    return { selectedMonth: this.selectedMonth, pending: this.pendingPostSave, highlightKey: this.highlight.key };
  },

  queueScroll(locate: LocatedTransaction, generation: number) {
    if (!canRunLocate(this.loadGuard.pageAlive, generation, this.locateGeneration)) {
      return;
    }
    if (locate.disposition === "top") {
      wx.pageScrollTo({ scrollTop: 0, duration: 300 });
      return;
    }
    wx.createSelectorQuery()
      .select(`#${locate.key}`)
      .boundingClientRect()
      .selectViewport()
      .boundingClientRect()
      .selectViewport()
      .scrollOffset()
      .exec((result) => {
        if (!canRunLocate(this.loadGuard.pageAlive, generation, this.locateGeneration)) {
          return;
        }
        const measurement = readScrollMeasurement(result);
        if (measurement === null) {
          return;
        }
        wx.pageScrollTo({
          scrollTop: centeredScrollTop(
            measurement.scrollTop + measurement.top,
            measurement.height,
            measurement.viewportHeight,
          ),
          duration: 300,
        });
      });
  },

  armHighlightTimer(generation: number) {
    this.stopHighlightTimer();
    this.highlightTimer = setTimeout(() => {
      this.highlightTimer = null;
      this.highlight = finishHighlight(this.highlight, generation);
      if (this.loadGuard.pageAlive) {
        this.setData({ highlightKey: this.highlight.key });
      }
    }, HIGHLIGHT_MS);
  },

  stopHighlightTimer() {
    if (this.highlightTimer === null) {
      return;
    }
    clearTimeout(this.highlightTimer);
    this.highlightTimer = null;
  },

  stopLocateFeedback() {
    this.locateGeneration = nextLocateGeneration(this.locateGeneration);
    this.highlight = clearHighlight(this.highlight);
    this.stopHighlightTimer();
  },
});

function readScrollMeasurement(result: unknown): { top: number; height: number; viewportHeight: number; scrollTop: number } | null {
  if (!Array.isArray(result) || result.length < 3) {
    return null;
  }
  const rect = readBox(result[0]);
  const viewport = readBox(result[1]);
  const scroll = readScroll(result[2]);
  if (rect === null || viewport === null || scroll === null) {
    return null;
  }
  return { top: rect.top, height: rect.height, viewportHeight: viewport.height, scrollTop: scroll.scrollTop };
}

function readBox(value: unknown): { top: number; height: number } | null {
  if (!isRecord(value) || typeof value.top !== "number" || typeof value.height !== "number") {
    return null;
  }
  return { top: value.top, height: value.height };
}

function readScroll(value: unknown): { scrollTop: number } | null {
  if (!isRecord(value) || typeof value.scrollTop !== "number") {
    return null;
  }
  return { scrollTop: value.scrollTop };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}
