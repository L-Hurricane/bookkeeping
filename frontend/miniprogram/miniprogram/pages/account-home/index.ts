import { formatCurrentBalance, routeAfterAccountQuery, type Account } from "../../account/account";
import { beginLoad, beginLogout, canApplyLoadResult, createLoadGuard, endLogout } from "../../account/load-guard";
import { accountApi, authApi, authNavigation } from "../../services/runtime";

Page({
  loadGuard: createLoadGuard(),

  data: {
    phase: "loading" as "loading" | "ready" | "error",
    name: "",
    balanceLabel: "",
    errorMessage: "",
    loggingOut: false,
  },

  onShow() {
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
    this.setData({ phase: "loading", errorMessage: "" });
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
        this.setData({ phase: "error", errorMessage: route.message });
        return;
      }
      this.showAccount(route.account);
    } catch {
      if (!canApplyLoadResult(this.loadGuard, generation)) {
        return;
      }
      this.setData({ phase: "error", errorMessage: "账户读取失败，请稍后重试" });
    }
  },

  showAccount(account: Account) {
    this.setData({
      phase: "ready",
      name: account.name,
      balanceLabel: formatCurrentBalance(account),
      errorMessage: "",
    });
  },

  onRetry() {
    void this.loadAccount();
  },

  async onLogout() {
    if (!beginLogout(this.loadGuard)) {
      return;
    }
    this.setData({ loggingOut: true });
    try {
      await authApi.logout();
      authNavigation.enterLogin();
    } catch {
      endLogout(this.loadGuard);
      this.setData({ loggingOut: false, phase: "error", errorMessage: "退出失败，请稍后重试" });
    }
  },
});
