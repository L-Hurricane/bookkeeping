import { ACCOUNT_CONTRACT_MESSAGE, routeAfterAccountQuery } from "../../account/account";
import { beginLoad, beginLogout, canApplyLoadResult, createLoadGuard, endLogout } from "../../account/load-guard";
import { routeBootstrap } from "../../navigation/startup";
import { restoreSession } from "../../session/session";
import { AccountResponseError } from "../../services/account";
import { accountApi, authApi, authNavigation } from "../../services/runtime";
import { wechatStorage } from "../../storage/wechat-storage";

Page({
  loadGuard: createLoadGuard(),

  data: {
    phase: "loading" as "loading" | "error",
    errorMessage: "",
    loggingOut: false,
  },

  onShow() {
    if (routeBootstrap(restoreSession(wechatStorage, new Date()) !== null) === "login") {
      authNavigation.enterLogin();
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
      if (route.destination === "home") {
        wx.reLaunch({ url: "/pages/account-home/index" });
        return;
      }
      this.setData({ phase: "error", errorMessage: route.message });
    } catch (error) {
      if (!canApplyLoadResult(this.loadGuard, generation)) {
        return;
      }
      const errorMessage =
        error instanceof AccountResponseError ? ACCOUNT_CONTRACT_MESSAGE : "账户读取失败，请稍后重试";
      this.setData({ phase: "error", errorMessage });
    }
  },

  onRetry() {
    void this.loadAccounts();
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
