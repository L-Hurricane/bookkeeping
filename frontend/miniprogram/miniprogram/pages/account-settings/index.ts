import { accountKindLabel, findAccount, type Account } from "../../account/account";
import { beginLoad, canApplyLoadResult, createLoadGuard } from "../../account/load-guard";
import { parseAccountSettingsQuery } from "../../account/navigation";
import { ApiError } from "../../http/client";
import { AccountResponseError } from "../../services/account";
import { isUnauthorized } from "../../services/ledger";
import { accountApi } from "../../services/runtime";

Page({
  loadGuard: createLoadGuard(),
  accountId: 0,
  account: null as Account | null,

  data: {
    phase: "loading" as "invalid" | "loading" | "ready" | "error",
    kindLabel: "",
    name: "",
    submitting: false,
    errorMessage: "",
  },

  onLoad(query: Record<string, string | undefined>) {
    const context = parseAccountSettingsQuery(query);
    if (context === null) {
      this.setData({ phase: "invalid" });
      return;
    }
    this.accountId = context.accountId;
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
      const account = findAccount(items, this.accountId);
      if (account === null) {
        this.setData({ phase: "error", errorMessage: "账户不存在或无权访问" });
        return;
      }
      this.account = account;
      this.setData({
        phase: "ready",
        kindLabel: accountKindLabel(account.kind),
        name: account.name,
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
    void this.loadAccount();
  },

  onBack() {
    if (this.data.submitting) {
      return;
    }
    wx.navigateBack();
  },

  onNameInput(event: WechatMiniprogram.Input) {
    this.setData({ name: event.detail.value, errorMessage: "" });
  },

  async onSave() {
    if (this.data.submitting || this.data.phase !== "ready") {
      return;
    }
    const name = this.data.name.trim();
    if (name.length === 0) {
      this.setData({ errorMessage: "请输入账户名称" });
      return;
    }
    if (name.length > 64) {
      this.setData({ errorMessage: "账户名称最长 64 个字符" });
      return;
    }
    this.setData({ submitting: true, errorMessage: "" });
    try {
      const account = await accountApi.renameAccount(this.accountId, name);
      this.account = account;
      this.setData({ submitting: false, name: account.name });
      wx.showToast({ title: "已保存", icon: "success" });
      setTimeout(() => {
        wx.navigateBack();
      }, 400);
    } catch (error) {
      if (isUnauthorized(error)) {
        return;
      }
      this.setData({ submitting: false, errorMessage: renameFailureMessage(error) });
    }
  },
});

function renameFailureMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === "ACCOUNT_NAME_CONFLICT") {
      return "账户名称已存在";
    }
    if (error.code === "VALIDATION_FAILED") {
      return error.message.length > 0 ? error.message : "请检查填写内容";
    }
    if (error.status === 404) {
      return "账户不存在或无权访问";
    }
  }
  if (error instanceof AccountResponseError) {
    return error.message;
  }
  return "保存失败，请稍后重试";
}
