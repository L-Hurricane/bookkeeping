import type { AccountKind } from "../../account/account";
import { accountHomePath } from "../../account/navigation";
import { normalizeOpeningBalance } from "../../account/opening-balance";
import { ApiError } from "../../http/client";
import { AccountResponseError } from "../../services/account";
import { isUnauthorized } from "../../services/ledger";
import { accountApi } from "../../services/runtime";

Page({
  data: {
    name: "",
    kind: "NORMAL" as AccountKind,
    openingBalance: "",
    submitting: false,
    errorMessage: "",
  },

  onNameInput(event: WechatMiniprogram.Input) {
    this.setData({ name: event.detail.value, errorMessage: "" });
  },

  onOpeningBalanceInput(event: WechatMiniprogram.Input) {
    this.setData({ openingBalance: event.detail.value, errorMessage: "" });
  },

  onKind(event: WechatMiniprogram.BaseEvent) {
    if (this.data.submitting) {
      return;
    }
    const kind = event.currentTarget.dataset["kind"];
    if (kind !== "NORMAL" && kind !== "CREDIT") {
      return;
    }
    this.setData({ kind, errorMessage: "" });
  },

  onCancel() {
    if (this.data.submitting) {
      return;
    }
    wx.navigateBack();
  },

  async onSubmit() {
    if (this.data.submitting) {
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
    const openingBalance = normalizeOpeningBalance(this.data.openingBalance);
    if (openingBalance === null) {
      this.setData({ errorMessage: "请输入最多两位小数的起始金额" });
      return;
    }
    this.setData({ submitting: true, errorMessage: "", openingBalance });
    try {
      const account = await accountApi.createAccount({
        name,
        kind: this.data.kind,
        openingBalance,
      });
      wx.redirectTo({ url: accountHomePath(account.id) });
    } catch (error) {
      if (isUnauthorized(error)) {
        return;
      }
      this.setData({ submitting: false, errorMessage: createFailureMessage(error) });
    }
  },
});

function createFailureMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === "ACCOUNT_NAME_CONFLICT") {
      return "账户名称已存在";
    }
    if (error.code === "VALIDATION_FAILED") {
      return error.message.length > 0 ? error.message : "请检查填写内容";
    }
    if (error.status === 403 || error.status === 404) {
      return "无权创建账户";
    }
  }
  if (error instanceof AccountResponseError) {
    return error.message;
  }
  return "创建失败，请稍后重试";
}
