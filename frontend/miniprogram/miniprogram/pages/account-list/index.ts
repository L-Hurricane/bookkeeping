import {
  accountKindLabel,
  formatCurrentBalance,
  routeActiveAccounts,
  shouldWarnNegativeBalance,
  type Account,
} from "../../account/account";
import { beginLoad, beginLogout, canApplyLoadResult, createLoadGuard, endLogout } from "../../account/load-guard";
import { moneyToneClass } from "../../account/money-tone";
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
import { accountApi, authApi, authNavigation } from "../../services/runtime";
import { restoreSession } from "../../session/session";
import { ledgerStatisticsEntryPath } from "../../statistics/drilldown";
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

  data: {
    phase: "loading" as "loading" | "ready" | "error",
    rows: [] as AccountRow[],
    hasArchived: false,
    errorMessage: "",
    refreshError: "",
    loggingOut: false,
    logoutErrorMessage: "",
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
      this.setData({
        phase: "ready",
        rows: route.accounts.map(toRow),
        hasArchived: archived.length > 0,
        errorMessage: "",
        refreshError: "",
      });
      this.acknowledgeArchive(route.accounts);
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

  onOpenStatistics() {
    if (this.loadGuard.loggingOut) {
      return;
    }
    wx.navigateTo({ url: ledgerStatisticsEntryPath() });
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
