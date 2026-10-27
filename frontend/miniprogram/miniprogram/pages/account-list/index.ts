import {
  accountKindLabel,
  formatCurrentBalance,
  routeActiveAccounts,
  shouldWarnNegativeBalance,
  type Account,
} from "../../account/account";
import { beginLoad, beginLogout, canApplyLoadResult, createLoadGuard, endLogout } from "../../account/load-guard";
import { moneyToneClass } from "../../account/money-tone";
import { entryPath, type EntryKind } from "../../entry/fab";
import { allTransactionsPath } from "../../ledger/feed-contract";
import { formatMoneyLabel } from "../../ledger/ledger";
import {
  accountArchivePath,
  accountCreatePath,
  accountHomePath,
  accountListReloadMode,
  archiveArrivalFeedback,
  parseAccountListNotice,
} from "../../account/navigation";
import { logoutFailureView } from "../../account/logout-view";
import { routeBootstrap } from "../../navigation/startup";
import { isUnauthorized } from "../../services/ledger";
import { accountApi, authApi, authNavigation, ledgerStatisticsApi } from "../../services/runtime";
import { restoreSession } from "../../session/session";
import { wechatStorage } from "../../storage/wechat-storage";

interface AccountRow {
  readonly id: number;
  readonly name: string;
  readonly kindLabel: string;
  readonly balanceLabel: string;
  readonly balanceToneClass: string;
  readonly negativeHint: string;
}

Page({
  loadGuard: createLoadGuard(),
  leaveToLogin: false,
  archiveNoticeAccountId: null as number | null,
  accounts: [] as Account[],
  summaryGeneration: 0,
  fabKind: "EXPENSE" as EntryKind,

  data: {
    phase: "loading" as "loading" | "ready" | "error",
    rows: [] as AccountRow[],
    hasArchived: false,
    errorMessage: "",
    refreshError: "",
    loggingOut: false,
    logoutErrorMessage: "",
    summaryReady: false,
    summaryError: "",
    netLabel: "",
    incomeLabel: "",
    expenseLabel: "",
    fabOpen: false,
    fabPicking: false,
    fabAccounts: [] as { id: number; name: string }[],
  },

  onLoad(query: Record<string, string | undefined>) {
    const notice = parseAccountListNotice(query);
    this.archiveNoticeAccountId = notice === null ? null : notice.accountId;
    if (routeBootstrap(restoreSession(wechatStorage, new Date()) !== null) === "login") {
      this.leaveToLogin = true;
      authNavigation.enterLogin();
    }
  },

  onShow() {
    if (this.leaveToLogin) {
      return;
    }
    void this.loadAccounts();
  },

  onUnload() {
    this.loadGuard.pageAlive = false;
  },

  async loadAccounts() {
    const generation = beginLoad(this.loadGuard);
    if (generation === null) {
      return;
    }
    const blocking = accountListReloadMode(this.data.phase) === "blocking";
    if (blocking) {
      this.setData({ phase: "loading", errorMessage: "", refreshError: "", logoutErrorMessage: "" });
    } else {
      this.setData({ refreshError: "", logoutErrorMessage: "" });
    }
    try {
      const items = await accountApi.listAccounts();
      if (!canApplyLoadResult(this.loadGuard, generation)) {
        return;
      }
      const archived = items.length === 0 ? await accountApi.listAccounts("ARCHIVED") : [];
      if (!canApplyLoadResult(this.loadGuard, generation)) {
        return;
      }
      const route = routeActiveAccounts(items, archived);
      if (route.destination === "init") {
        wx.reLaunch({ url: "/pages/account-init/index" });
        return;
      }
      if (route.destination === "error") {
        this.failLoad(blocking, route.message);
        return;
      }
      this.accounts = [...route.accounts];
      this.setData({
        phase: "ready",
        rows: route.accounts.map(toRow),
        hasArchived: archived.length > 0,
        errorMessage: "",
        refreshError: "",
        fabAccounts: route.accounts.map((account) => ({ id: account.id, name: account.name })),
      });
      this.acknowledgeArchive(route.accounts);
      void this.loadSummary();
    } catch (error) {
      if (!canApplyLoadResult(this.loadGuard, generation) || isUnauthorized(error)) {
        return;
      }
      this.failLoad(blocking, "账户读取失败，请稍后重试");
    }
  },

  failLoad(blocking: boolean, message: string) {
    if (blocking) {
      this.setData({ phase: "error", errorMessage: message, refreshError: "" });
      return;
    }
    this.setData({ refreshError: message });
  },

  acknowledgeArchive(accounts: readonly Account[]) {
    const accountId = this.archiveNoticeAccountId;
    if (accountId === null) {
      return;
    }
    if (archiveArrivalFeedback(accounts, accountId) !== "success") {
      this.setData({ refreshError: "账户状态未更新，请稍后重试" });
      return;
    }
    this.archiveNoticeAccountId = null;
    wx.showToast({ title: "账户已归档", icon: "success" });
  },

  onRetry() {
    void this.loadAccounts();
  },

  onOpenAccount(event: WechatMiniprogram.BaseEvent) {
    if (this.loadGuard.loggingOut) {
      return;
    }
    const accountId = Number(event.currentTarget.dataset["id"]);
    if (!Number.isSafeInteger(accountId) || accountId <= 0) {
      return;
    }
    wx.navigateTo({ url: accountHomePath(accountId) });
  },

  onCreateAccount() {
    if (this.loadGuard.loggingOut) {
      return;
    }
    wx.navigateTo({ url: accountCreatePath() });
  },

  onOpenArchive() {
    if (this.loadGuard.loggingOut) {
      return;
    }
    wx.navigateTo({ url: accountArchivePath() });
  },

  onOpenAll() {
    if (this.loadGuard.loggingOut) return;
    wx.navigateTo({ url: allTransactionsPath({}) });
  },

  onToggleFab() {
    this.setData({ fabOpen: !this.data.fabOpen, fabPicking: false });
  },

  onFabExpense() { this.beginEntry("EXPENSE"); },
  onFabIncome() { this.beginEntry("INCOME"); },
  onFabTransfer() { this.beginEntry("TRANSFER"); },

  onPickAccount(event: WechatMiniprogram.BaseEvent) {
    const id = Number(event.currentTarget.dataset["id"]);
    const account = this.accounts.find((item) => item.id === id);
    if (account === undefined || this.loadGuard.loggingOut) return;
    this.setData({ fabOpen: false, fabPicking: false });
    wx.navigateTo({ url: entryPath(this.fabKind, account) });
  },

  beginEntry(kind: EntryKind) {
    if (this.loadGuard.loggingOut || this.accounts.length === 0) return;
    this.fabKind = kind;
    const only = this.accounts.length === 1 ? this.accounts[0] : undefined;
    if (only !== undefined) {
      this.setData({ fabOpen: false, fabPicking: false });
      wx.navigateTo({ url: entryPath(kind, only) });
      return;
    }
    this.setData({ fabOpen: true, fabPicking: true });
  },

  async loadSummary() {
    const generation = ++this.summaryGeneration;
    try {
      const report = await ledgerStatisticsApi.load({ preset: "MONTH", accountScope: "ACTIVE" });
      if (!canApplyLoadResult(this.loadGuard, this.loadGuard.generation) || generation !== this.summaryGeneration) return;
      this.setData({
        summaryReady: true,
        summaryError: "",
        netLabel: formatMoneyLabel(report.netWorth.closing),
        incomeLabel: formatMoneyLabel(report.income.net),
        expenseLabel: formatMoneyLabel(report.expense.net),
      });
    } catch (error) {
      if (!canApplyLoadResult(this.loadGuard, this.loadGuard.generation) || isUnauthorized(error) || generation !== this.summaryGeneration) return;
      this.setData({ summaryReady: false, summaryError: "本月汇总暂时没有读取成功" });
    }
  },

  async onLogout() {
    if (!beginLogout(this.loadGuard)) {
      return;
    }
    this.setData({ loggingOut: true, logoutErrorMessage: "" });
    try {
      await authApi.logout();
      authNavigation.enterLogin();
    } catch {
      endLogout(this.loadGuard);
      this.setData(logoutFailureView({ ...this.data, loggingOut: false }));
    }
  },
});

function toRow(account: Account): AccountRow {
  return {
    id: account.id,
    name: account.name,
    kindLabel: accountKindLabel(account.kind),
    balanceLabel: formatCurrentBalance(account),
    balanceToneClass: moneyToneClass(account.currentBalance),
    negativeHint: shouldWarnNegativeBalance(account) ? "余额为负，仍可继续记账" : "",
  };
}
