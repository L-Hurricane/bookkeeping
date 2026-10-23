import {
  accountKindLabel,
  formatCurrentBalance,
  routeActiveAccounts,
  shouldWarnNegativeBalance,
  type Account,
} from "../../account/account";
import { beginLoad, beginLogout, canApplyLoadResult, createLoadGuard, endLogout } from "../../account/load-guard";
import { moneyToneClass } from "../../account/money-tone";
import { accountArchivePath, accountCreatePath, accountHomePath } from "../../account/navigation";
import { logoutFailureView } from "../../account/logout-view";
import { isUnauthorized } from "../../services/ledger";
import { accountApi, authApi, authNavigation } from "../../services/runtime";

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

  data: {
    phase: "loading" as "loading" | "ready" | "error",
    rows: [] as AccountRow[],
    hasArchived: false,
    errorMessage: "",
    loggingOut: false,
    logoutErrorMessage: "",
  },

  onShow() {
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
    this.setData({ phase: "loading", errorMessage: "", logoutErrorMessage: "" });
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
        this.setData({ phase: "error", errorMessage: route.message });
        return;
      }
      this.setData({
        phase: "ready",
        rows: route.accounts.map(toRow),
        hasArchived: archived.length > 0,
        errorMessage: "",
      });
    } catch (error) {
      if (!canApplyLoadResult(this.loadGuard, generation) || isUnauthorized(error)) {
        return;
      }
      this.setData({ phase: "error", errorMessage: "账户读取失败，请稍后重试" });
    }
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
