import { formatCurrentBalance, routeAfterAccountQuery, type Account } from "../../account/account";
import { logoutFailureView, resumeAfterLogoutFailure, selectedMonthDuringLogout } from "../../account/logout-view";
import { beginLoad, beginLogout, canApplyLoadResult, createLoadGuard, endLogout } from "../../account/load-guard";
import { canApplyLedgerResult, formatMoneyLabel, ledgerRows } from "../../ledger/ledger";
import { currentBeijingMonth, formatMonthLabel, nextMonth, previousMonth } from "../../ledger/month";
import {
  acceptAdjustmentCreated,
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
  settleLedger,
  startHighlight,
  type LedgerLocateIntent,
  type LocatedTransaction,
} from "../../ledger/post-save";
import { accountApi, authApi, authNavigation, ledgerApi } from "../../services/runtime";
import { validateBusinessTime } from "../../transaction/business-time";
import { adjustmentCreatePath, adjustmentDetailPath, canOpenAdjustmentDetail, canOpenBalanceAdjustment } from "../../balance-adjustment/navigation";
import { statisticsPagePath } from "../../statistics/drilldown";
import { canOpenStatistics } from "../../statistics/navigation";
import { canOpenCreateTransaction, canOpenTransactionDetail, transactionCreatePath, transactionDetailPath } from "../../transaction/navigation";
import { isUnauthorized, LedgerResponseError } from "../../services/ledger";

Page({
  loadGuard: createLoadGuard(),
  selectedMonth: "",
  accountId: 0,
  accountCreatedTime: "",
  currency: "",
  pendingPostSave: null as LedgerLocateIntent | null,
  highlight: idleHighlight(),
  highlightTimer: null as number | null,
  locateGeneration: 0,

  data: {
    phase: "account-loading" as "account-loading" | "account-error" | "ledger-loading" | "ready" | "ledger-error",
    name: "",
    balanceLabel: "",
    monthLabel: "",
    incomeLabel: "",
    expenseLabel: "",
    surplusLabel: "",
    countLabel: "",
    rows: [] as ReturnType<typeof ledgerRows>,
    empty: false,
    errorMessage: "",
    loggingOut: false,
    logoutErrorMessage: "",
    highlightKey: "",
    locateMiss: "",
  },

  onShow() {
    if (this.selectedMonth.length === 0) {
      this.selectedMonth = currentBeijingMonth(new Date());
    }
    void this.loadAccount();
  },

  onUnload() {
    this.loadGuard.pageAlive = false;
    this.stopLocateFeedback();
  },

  async loadAccount() {
    const generation = beginLoad(this.loadGuard);
    if (generation === null) {
      return;
    }
    this.setData({ phase: "account-loading", errorMessage: "" });
    try {
      const items = await accountApi.listAccounts();
      if (!canApplyLoadResult(this.loadGuard, generation)) {
        return;
      }
      const route = routeAfterAccountQuery(items);
      if (route.destination === "init") {
        wx.reLaunch({ url: "/pages/account-init/index" });
        return;
      }
      if (route.destination === "error") {
        this.setData({ phase: "account-error", errorMessage: route.message });
        return;
      }
      this.accountId = route.account.id;
      this.accountCreatedTime = route.account.createdTime;
      this.currency = route.account.currency;
      this.showAccount(route.account);
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
      balanceLabel: formatCurrentBalance(account),
      monthLabel: formatMonthLabel(this.selectedMonth),
      errorMessage: "",
    });
  },

  async loadLedger() {
    const month = this.selectedMonth;
    const accountId = this.accountId;
    const generation = beginLoad(this.loadGuard);
    if (generation === null) {
      return;
    }
    this.setData({ phase: "ledger-loading", monthLabel: formatMonthLabel(month), errorMessage: "" });
    try {
      const ledger = await ledgerApi.getLedger(accountId, month);
      if (!canApplyLedgerResult(this.loadGuard, generation, month, this.selectedMonth)) {
        return;
      }
      const settlement = settleLedger(this.pendingPostSave, "success", ledger.items);
      this.pendingPostSave = settlement.pending;
      const locate = settlement.locate;
      const ledgerView = {
        phase: "ready" as const,
        balanceLabel: this.currency === "CNY" ? formatMoneyLabel(ledger.currentBalance) : `${ledger.currentBalance} ${this.currency}`,
        monthLabel: formatMonthLabel(ledger.month),
        incomeLabel: formatMoneyLabel(ledger.summary.netIncome),
        expenseLabel: formatMoneyLabel(ledger.summary.netExpense),
        surplusLabel: formatMoneyLabel(ledger.summary.surplus),
        countLabel: `${ledger.summary.transactionCount} 笔收支`,
        rows: ledgerRows(ledger.items),
        empty: ledger.items.length === 0,
        errorMessage: "",
        locateMiss: settlement.missMessage,
        highlightKey: locate === null ? this.highlight.key : locate.key,
      };
      if (locate === null) {
        this.setData(ledgerView);
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
      const errorMessage = error instanceof LedgerResponseError ? "流水数据不符合约定" : "流水读取失败，请稍后重试";
      this.setData({ phase: "ledger-error", errorMessage });
    }
  },

  onCreate() {
    if (!canOpenCreateTransaction(this.data.phase, this.loadGuard.loggingOut) || !validateBusinessTime(this.accountCreatedTime)) {
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
    if (!canOpenBalanceAdjustment(this.data.phase, this.loadGuard.loggingOut) || !validateBusinessTime(this.accountCreatedTime)) {
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

  onOpenRecord(event: WechatMiniprogram.BaseEvent) {
    const kind = event.currentTarget.dataset["kind"];
    const recordId = Number(event.currentTarget.dataset["id"]);
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
    wx.navigateTo({
      url: transactionDetailPath(this.accountId, recordId, this.accountCreatedTime, this.selectedMonth),
      events: {
        transactionUpdated: (result: unknown) => {
          this.receiveTransactionCreated(result);
        },
      },
    });
  },

  receiveAdjustmentCreated(payload: unknown) {
    const current = this.locateNavigation();
    this.receiveLocate(current, acceptAdjustmentCreated(current, payload));
  },

  receiveTransactionCreated(payload: unknown) {
    const current = this.locateNavigation();
    this.receiveLocate(current, acceptTransactionCreated(current, payload));
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
    this.stopLocateFeedback();
    this.setData({ highlightKey: "", locateMiss: "" });
    void this.loadLedger();
  },

  onRetry() {
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
