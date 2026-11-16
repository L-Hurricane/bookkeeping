import { ARCHIVED_HISTORY_HINT } from "../../account/archive-copy";
import {
  formatCurrentBalance,
  shouldWarnNegativeBalance,
  type Account,
} from "../../account/account";
import { stashLedgerHandoff, takeLedgerHandoff } from "../../account/ledger-handoff";
import { selectedMonthDuringLogout } from "../../account/logout-view";
import { beginLoad, canApplyLoadResult, createLoadGuard } from "../../account/load-guard";
import { moneyToneClass } from "../../account/money-tone";
import { accountHomePath, accountListPath, accountSettingsPath, parseAccountHomeQuery } from "../../account/navigation";
import {
  accountLedgerEmptyText,
  accountLedgerFilterKey,
  accountLedgerFilterLabel,
  appendLedgerPage,
  isMonth,
  isUsableDateRange,
  type AccountLedgerFilter,
} from "../../ledger/account-history";
import { groupByTimeLabel } from "../../ledger/day-groups";
import { canApplyLedgerResult, formatMoneyLabel, ledgerRows, type LedgerEntry } from "../../ledger/ledger";
import { beijingDate, currentBeijingMonth, formatMonthLabel, monthLastDate, nextMonth, previousMonth } from "../../ledger/month";
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
  nextLocateCursor,
  parsePostSaveResult,
  settleLedger,
  startHighlight,
  type LedgerLocateIntent,
  type LocatedTransaction,
} from "../../ledger/post-save";
import { accountApi, ledgerApi, ledgerStatisticsApi } from "../../services/runtime";
import {
  createSearchDebounce,
  invalidateSearchOnInputChange,
  isActiveSearchKeyword,
  nextSearchGeneration,
  SEARCH_DEBOUNCE_MS,
  buildLedgerLoadSetDataPatch,
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
import { accountStatisticsTarget } from "../../statistics/tab-context";
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
  filterMode: "all" as AccountLedgerFilter["mode"],
  rangeFrom: "",
  rangeTo: "",
  loadedItems: [] as LedgerEntry[],
  nextCursor: null as string | null,
  loadingMore: false,
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
    monthLabel: "全部时间",
    filterMode: "all" as "all" | "month" | "range",
    showMonthSummary: false,
    emptyText: "暂无记录",
    rangeFrom: "",
    rangeTo: "",
    rangeError: "",
    choosingRange: false,
    loadingMore: false,
    moreError: "",
    reachedEnd: false,
    searchActive: false,
    incomeLabel: "",
    incomeToneClass: "money-zero",
    expenseLabel: "",
    expenseToneClass: "money-zero",
    surplusLabel: "",
    surplusToneClass: "money-zero",
    countLabel: "",
    rows: [] as ReturnType<typeof ledgerRows>,
    groups: [] as ReturnType<typeof groupByTimeLabel<ReturnType<typeof ledgerRows>[number]>>,
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
    monthCloseCaption: "",
    monthCloseLabel: "",
    monthCloseTone: "money-zero",
    fabOpen: false,
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
    if (handoff !== null && isMonth(handoff.targetMonth)) {
      this.pendingPostSave = handoff;
      this.selectedMonth = handoff.targetMonth;
      this.filterMode = "month";
    }
  },

  onShow() {
    if (!this.contextReady) {
      return;
    }
    const shown = showOpenList(this.openList());
    this.detailReturn = shown.state.visit;
    if (shown.show.refresh === "keep") {
      return;
    }
    if (shown.show.refresh === "background-refresh") {
      void this.loadLedger("background", shown.show.scrollTop);
      return;
    }
    if (shown.show.scope === "ledger") {
      void this.loadLedger("replace");
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
      monthLabel: accountLedgerFilterLabel(this.historyFilter()),
      errorMessage: "",
    });
  },

  async loadLedger(presentation: "replace" | "background" = "replace", restoreScrollTop: number | null = null) {
    const filter = this.historyFilter();
    const keyword = this.activeKeyword();
    const filterKey = this.listKey(filter, keyword);
    const background = presentation === "background" && this.data.phase === "ready";
    const scrollRestoreGeneration = ++this.scrollRestoreGeneration;
    const generation = beginLoad(this.loadGuard);
    if (generation === null) {
      return;
    }
    this.nextCursor = null;
    this.loadedItems = [];
    this.loadingMore = false;
    if (!background) {
      this.setData({
        phase: "ledger-loading",
        monthLabel: accountLedgerFilterLabel(filter),
        filterMode: filter.mode,
        showMonthSummary: filter.mode === "month",
        emptyText: accountLedgerEmptyText(filter),
        errorMessage: "",
        moreError: "",
        loadingMore: false,
        reachedEnd: false,
        monthCloseLabel: "",
        monthCloseCaption: "",
        monthCloseTone: "money-zero",
      });
    }
    if (filter.mode === "month") void this.loadMonthClose(filter.month);
    try {
      let ledger = await ledgerApi.getLedgerPage(this.pageQuery(filter, null, keyword));
      if (!canApplyLedgerResult(this.loadGuard, generation, filterKey, this.currentListKey())) {
        return;
      }
      let items = ledger.items.slice();
      let cursor = ledger.nextCursor;
      const seenCursors = new Set<string>();
      let stalled = false;
      while (true) {
        const step = nextLocateCursor(this.pendingPostSave, items, cursor);
        if (step === null) {
          break;
        }
        if (seenCursors.has(step)) {
          stalled = true;
          break;
        }
        seenCursors.add(step);
        const page = await ledgerApi.getLedgerPage(this.pageQuery(filter, step, keyword));
        if (!canApplyLedgerResult(this.loadGuard, generation, filterKey, this.currentListKey())) {
          return;
        }
        items = appendLedgerPage(items, page.items);
        cursor = page.nextCursor;
        ledger = page;
      }
      this.loadedItems = items;
      this.nextCursor = cursor;
      const morePages = !stalled && nextLocateCursor(this.pendingPostSave, items, cursor) !== null;
      const settlement = settleLedger(this.pendingPostSave, "success", this.loadedItems, morePages);
      this.pendingPostSave = settlement.pending;
      const locate = settlement.locate;
      const explicitLocate = this.pendingPostSave !== null || locate !== null;
      const restoreTop = background && !explicitLocate ? restoreScrollTop : null;
      const ledgerView = buildLedgerLoadSetDataPatch(this.searchKeyword, this.loadedItems, this.ledgerChrome(ledger, filter, {
        locateMiss: settlement.missMessage,
        highlightKey: locate === null ? this.highlight.key : locate.key,
      }));
      if (locate === null) {
        this.setData(withDayGroups(ledgerView), () => {
          if (scrollRestoreGeneration !== this.scrollRestoreGeneration || this.currentListKey() !== filterKey) {
            return;
          }
          this.restoreListScroll(restoreTop);
        });
        return;
      }
      this.highlight = startHighlight(this.highlight, locate.key);
      this.locateGeneration = nextLocateGeneration(this.locateGeneration);
      const captured = this.locateGeneration;
      this.setData(withDayGroups(ledgerView), () => {
        if (!canRunLocate(this.loadGuard.pageAlive, captured, this.locateGeneration)) {
          return;
        }
        this.queueScroll(locate, captured);
        this.armHighlightTimer(this.highlight.generation);
      });
    } catch (error) {
      if (!canApplyLedgerResult(this.loadGuard, generation, filterKey, this.currentListKey()) || isUnauthorized(error)) {
        return;
      }
      if (background && this.data.phase === "ready") {
        return;
      }
      const errorMessage = error instanceof LedgerResponseError ? "流水数据不符合约定" : "流水读取失败，请稍后重试";
      this.setData({ phase: "ledger-error", errorMessage });
    }
  },

  async loadMore() {
    const cursor = this.nextCursor;
    if (cursor === null || this.loadingMore || this.data.phase !== "ready") {
      return;
    }
    const filter = this.historyFilter();
    const keyword = this.activeKeyword();
    const filterKey = this.listKey(filter, keyword);
    const generation = this.loadGuard.generation;
    this.loadingMore = true;
    this.setData({ loadingMore: true, moreError: "" });
    try {
      const ledger = await ledgerApi.getLedgerPage(this.pageQuery(filter, cursor, keyword));
      if (
        !canApplyLedgerResult(this.loadGuard, generation, filterKey, this.currentListKey()) ||
        this.nextCursor !== cursor
      ) {
        return;
      }
      this.loadedItems = appendLedgerPage(this.loadedItems, ledger.items);
      this.nextCursor = ledger.nextCursor;
      const ledgerView = buildLedgerLoadSetDataPatch(this.searchKeyword, this.loadedItems, this.ledgerChrome(ledger, filter, {
        locateMiss: this.data.locateMiss,
        highlightKey: this.data.highlightKey,
      }));
      this.setData(withDayGroups(ledgerView));
    } catch (error) {
      if (
        !canApplyLedgerResult(this.loadGuard, generation, filterKey, this.currentListKey()) ||
        this.nextCursor !== cursor ||
        isUnauthorized(error)
      ) {
        return;
      }
      this.setData({ moreError: "加载失败，请重试" });
    } finally {
      this.loadingMore = false;
      if (this.loadGuard.pageAlive) this.setData({ loadingMore: false });
    }
  },

  onReachBottom() {
    if (this.data.moreError.length > 0) return;
    void this.loadMore();
  },

  onPullDownRefresh() {
    void this.loadLedger().finally(() => {
      wx.stopPullDownRefresh();
    });
  },

  onRetryMore() {
    void this.loadMore();
  },

  historyFilter(): AccountLedgerFilter {
    if (this.filterMode === "month" && isMonth(this.selectedMonth)) return { mode: "month", month: this.selectedMonth };
    if (this.filterMode === "range" && isUsableDateRange(this.rangeFrom, this.rangeTo)) {
      return { mode: "range", from: this.rangeFrom, to: this.rangeTo };
    }
    return { mode: "all" };
  },

  activeKeyword(): string {
    return isActiveSearchKeyword(this.searchKeyword) ? this.searchKeyword : "";
  },

  listKey(filter: AccountLedgerFilter, keyword: string): string {
    return `${accountLedgerFilterKey(filter)}\n${keyword}`;
  },

  currentListKey(): string {
    return this.listKey(this.historyFilter(), this.activeKeyword());
  },

  pageQuery(filter: AccountLedgerFilter, cursor: string | null, keyword: string) {
    return {
      accountId: this.accountId,
      month: filter.mode === "month" ? filter.month : null,
      from: filter.mode === "range" ? filter.from : null,
      to: filter.mode === "range" ? filter.to : null,
      name: keyword.length > 0 ? keyword : null,
      cursor,
    };
  },

  ledgerChrome(
    ledger: { currentBalance: string; summary: { netIncome: string; netExpense: string; surplus: string; transactionCount: number } },
    filter: AccountLedgerFilter,
    locate: { locateMiss: string; highlightKey: string },
  ) {
    const showMonthSummary = filter.mode === "month";
    return {
      phase: "ready" as const,
      balanceLabel: this.currency === "CNY" ? formatMoneyLabel(ledger.currentBalance) : `${ledger.currentBalance} ${this.currency}`,
      balanceToneClass: moneyToneClass(ledger.currentBalance),
      negativeBalanceHint:
        !this.archived && this.accountKind === "NORMAL" && moneyToneClass(ledger.currentBalance) === "money-negative"
          ? "余额为负，仍可继续记账"
          : "",
      monthLabel: accountLedgerFilterLabel(filter),
      filterMode: filter.mode,
      showMonthSummary,
      emptyText: accountLedgerEmptyText(filter),
      incomeLabel: showMonthSummary ? formatMoneyLabel(ledger.summary.netIncome) : "",
      incomeToneClass: showMonthSummary ? moneyToneClass(ledger.summary.netIncome) : "money-zero",
      expenseLabel: showMonthSummary ? formatMoneyLabel(ledger.summary.netExpense) : "",
      expenseToneClass: showMonthSummary ? moneyToneClass(ledger.summary.netExpense) : "money-zero",
      surplusLabel: showMonthSummary ? formatMoneyLabel(ledger.summary.surplus) : "",
      surplusToneClass: showMonthSummary ? moneyToneClass(ledger.summary.surplus) : "money-zero",
      countLabel: showMonthSummary ? `${ledger.summary.transactionCount} 笔收支` : "",
      errorMessage: "",
      moreError: "",
      loadingMore: false,
      reachedEnd: this.nextCursor === null,
      searchActive: isActiveSearchKeyword(this.searchKeyword),
      locateMiss: locate.locateMiss,
      highlightKey: locate.highlightKey,
    };
  },

  onToggleFab() {
    this.setData({ fabOpen: !this.data.fabOpen });
  },

  onFabExpense() {
    this.openTransaction("EXPENSE");
  },

  onFabIncome() {
    this.openTransaction("INCOME");
  },

  onFabTransfer() {
    this.setData({ fabOpen: false });
    this.onTransfer();
  },

  onFabAdjust() {
    this.setData({ fabOpen: false });
    this.onAdjust();
  },

  onCreate() {
    this.openTransaction("EXPENSE");
  },

  openTransaction(type: "EXPENSE" | "INCOME") {
    if (this.archived || !canOpenCreateTransaction(this.data.phase, this.loadGuard.loggingOut) || !validateBusinessTime(this.accountCreatedTime)) {
      return;
    }
    this.setData({ fabOpen: false });
    const path = transactionCreatePath(this.accountId, this.accountCreatedTime);
    wx.navigateTo({
      url: type === "INCOME" ? `${path}&type=INCOME` : path,
      events: {
        transactionCreated: (result: unknown) => {
          this.receiveTransactionCreated(result);
        },
      },
    });
  },

  onStatistics() {
    if (!canOpenStatistics(this.data.phase, this.loadGuard.loggingOut) || !validateBusinessTime(this.accountCreatedTime)) {
      return;
    }
    wx.switchTab({ url: accountStatisticsTarget(this.accountId) });
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

  async loadMonthClose(month: string) {
    const current = currentBeijingMonth(new Date());
    if (month > current) {
      this.setData({ monthCloseLabel: "", monthCloseCaption: "", monthCloseTone: "money-zero" });
      return;
    }
    const to = month === current ? beijingDate(new Date()) : monthLastDate(month);
    try {
      const report = await ledgerStatisticsApi.load({
        preset: "CUSTOM",
        from: `${month}-01`,
        to,
        accountScope: "SELECTED",
        accountIds: [this.accountId],
      });
      if (this.selectedMonth !== month || !this.loadGuard.pageAlive) return;
      this.setData({
        monthCloseCaption: month === current ? "截至今天结束" : "月末余额",
        monthCloseLabel: formatMoneyLabel(report.netWorth.closing),
        monthCloseTone: moneyToneClass(report.netWorth.closing),
      });
    } catch {
      if (this.selectedMonth !== month || !this.loadGuard.pageAlive) return;
      this.setData({ monthCloseLabel: "", monthCloseCaption: "", monthCloseTone: "money-zero" });
    }
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
    const day = event.currentTarget.dataset["day"];
    const originMonth = typeof day === "string" ? day.slice(0, 7) : "";
    if (!isMonth(originMonth)) return;
    this.detailReturn = { dirty: false, context: this.selectedMonth, scrollTop: this.pageScrollTop };
    wx.navigateTo({
      url: transactionDetailPath(this.accountId, recordId, this.accountCreatedTime, originMonth),
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
    const saved = parsePostSaveResult(result);
    if (saved !== null && saved.accountId > 0 && saved.accountId !== this.accountId) {
      this.detailReturn = noteListStale(this.openList()).visit;
      return;
    }
    const current = this.locateNavigation();
    const next = acceptTransactionCreated(current, result);
    const accepted = applyAcceptedLocate(current, next);
    const pending = accepted?.pending ?? null;
    const listed = noteSavedTransaction(this.openList(), pending?.targetMonth ?? null);
    this.detailReturn = listed.visit;
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
      this.resetListScroll();
      this.setData({ searchEmpty: false, searchErrorMessage: "", searchLoading: false, searchActive: false });
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
    this.searchKeyword = keyword;
    this.searchGeneration = nextSearchGeneration(this.searchGeneration);
    this.searchRequestGeneration = this.searchGeneration;
    this.resetListScroll();
    this.setData({ searchLoading: false, searchErrorMessage: "", searchEmpty: false, searchActive: true, reachedEnd: false, moreError: "" });
    await this.loadLedger();
  },

  resetSearchState() {
    this.searchDebounce.cancel();
    this.searchKeyword = "";
    this.searchGeneration = nextSearchGeneration(this.searchGeneration);
    this.searchRequestGeneration = this.searchGeneration;
    this.setData({ searchInput: "", searchLoading: false, searchEmpty: false, searchErrorMessage: "" });
  },

  onPreviousMonth() {
    if (this.filterMode !== "month" || !isMonth(this.selectedMonth)) return;
    this.changeMonth(previousMonth(this.selectedMonth));
  },

  onNextMonth() {
    if (this.filterMode !== "month" || !isMonth(this.selectedMonth)) return;
    this.changeMonth(nextMonth(this.selectedMonth));
  },

  onChooseAll() {
    if (this.filterMode === "all" && !this.data.choosingRange) return;
    this.filterMode = "all";
    this.selectedMonth = "";
    this.rangeFrom = "";
    this.rangeTo = "";
    this.resetHistoryAndLoad();
  },

  onChooseRange() {
    this.setData({ choosingRange: true, rangeError: "" });
  },

  onChooseMonth() {
    const month = isMonth(this.selectedMonth) ? this.selectedMonth : currentBeijingMonth(new Date());
    this.changeMonth(month);
  },

  onRangeFrom(event: WechatMiniprogram.PickerChange) {
    this.rangeFrom = typeof event.detail.value === "string" ? event.detail.value : "";
    this.setData({ rangeFrom: this.rangeFrom });
    this.applyRange();
  },

  onRangeTo(event: WechatMiniprogram.PickerChange) {
    this.rangeTo = typeof event.detail.value === "string" ? event.detail.value : "";
    this.setData({ rangeTo: this.rangeTo });
    this.applyRange();
  },

  applyRange() {
    if (!isUsableDateRange(this.rangeFrom, this.rangeTo)) {
      if (this.rangeFrom.length > 0 && this.rangeTo.length > 0) {
        this.setData({ rangeError: "日期范围无法使用" });
      }
      return;
    }
    this.filterMode = "range";
    this.selectedMonth = "";
    this.setData({ rangeError: "" });
    this.resetHistoryAndLoad();
  },

  resetListScroll() {
    this.pageScrollTop = 0;
    this.scrollRestoreGeneration += 1;
    wx.pageScrollTo({ scrollTop: 0, duration: 0 });
  },

  resetHistoryAndLoad() {
    this.pendingPostSave = null;
    this.detailReturn = null;
    this.pageScrollTop = 0;
    this.scrollRestoreGeneration += 1;
    this.stopLocateFeedback();
    this.setData({ highlightKey: "", locateMiss: "", rangeFrom: this.rangeFrom, rangeTo: this.rangeTo, rangeError: "", choosingRange: this.filterMode === "range" });
    wx.pageScrollTo({ scrollTop: 0, duration: 0 });
    void this.loadLedger();
  },

  changeMonth(month: string) {
    const selectedMonth = selectedMonthDuringLogout(this.loadGuard.loggingOut, this.selectedMonth, month);
    const next = changeSelectedMonth(this.locateNavigation(), selectedMonth);
    if (next.selectedMonth === this.selectedMonth && this.filterMode === "month") {
      return;
    }
    this.filterMode = "month";
    this.selectedMonth = next.selectedMonth;
    this.rangeFrom = "";
    this.rangeTo = "";
    this.pendingPostSave = next.pending;
    this.detailReturn = null;
    this.pageScrollTop = 0;
    this.scrollRestoreGeneration += 1;
    this.stopLocateFeedback();
    this.setData({ highlightKey: "", locateMiss: "", rangeFrom: "", rangeTo: "", rangeError: "", choosingRange: false });
    wx.pageScrollTo({ scrollTop: 0, duration: 0 });
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

function withDayGroups(view: ReturnType<typeof buildLedgerLoadSetDataPatch>) {
  if (!("rows" in view)) return view;
  return { ...view, groups: decorateGroups(view.rows) };
}

function decorateGroups(rows: ReturnType<typeof ledgerRows>) {
  let previous = "";
  return groupByTimeLabel(rows).map((group) => {
    const month = group.key.slice(0, 7);
    const valid = isMonth(month);
    const monthHeading = valid && month !== previous ? formatMonthLabel(month) : "";
    if (valid) previous = month;
    return { ...group, monthHeading };
  });
}

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
