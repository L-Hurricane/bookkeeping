import { formatCurrentBalance, routeAfterAccountQuery, type Account } from "../../account/account";
import { logoutFailureView, resumeAfterLogoutFailure, selectedMonthDuringLogout } from "../../account/logout-view";
import { beginLoad, beginLogout, canApplyLoadResult, createLoadGuard, endLogout } from "../../account/load-guard";
import { canApplyLedgerResult, formatMoneyLabel, ledgerRows } from "../../ledger/ledger";
import { currentBeijingMonth, formatMonthLabel, nextMonth, previousMonth } from "../../ledger/month";
import { accountApi, authApi, authNavigation, ledgerApi } from "../../services/runtime";
import { isUnauthorized, LedgerResponseError } from "../../services/ledger";

Page({
  loadGuard: createLoadGuard(),
  selectedMonth: "",
  accountId: 0,
  currency: "",

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
  },

  onShow() {
    if (this.selectedMonth.length === 0) {
      this.selectedMonth = currentBeijingMonth(new Date());
    }
    void this.loadAccount();
  },

  onUnload() {
    this.loadGuard.pageAlive = false;
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
      this.setData({
        phase: "ready",
        balanceLabel: this.currency === "CNY" ? formatMoneyLabel(ledger.currentBalance) : `${ledger.currentBalance} ${this.currency}`,
        monthLabel: formatMonthLabel(ledger.month),
        incomeLabel: formatMoneyLabel(ledger.summary.netIncome),
        expenseLabel: formatMoneyLabel(ledger.summary.netExpense),
        surplusLabel: formatMoneyLabel(ledger.summary.surplus),
        countLabel: `${ledger.summary.transactionCount} 笔收支`,
        rows: ledgerRows(ledger.items),
        empty: ledger.items.length === 0,
        errorMessage: "",
      });
    } catch (error) {
      if (!canApplyLedgerResult(this.loadGuard, generation, month, this.selectedMonth) || isUnauthorized(error)) {
        return;
      }
      const errorMessage = error instanceof LedgerResponseError ? "流水数据不符合约定" : "流水读取失败，请稍后重试";
      this.setData({ phase: "ledger-error", errorMessage });
    }
  },

  onPreviousMonth() {
    this.changeMonth(previousMonth(this.selectedMonth));
  },

  onNextMonth() {
    this.changeMonth(nextMonth(this.selectedMonth));
  },

  changeMonth(month: string) {
    const selectedMonth = selectedMonthDuringLogout(this.loadGuard.loggingOut, this.selectedMonth, month);
    if (selectedMonth === this.selectedMonth) {
      return;
    }
    this.selectedMonth = selectedMonth;
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
});
