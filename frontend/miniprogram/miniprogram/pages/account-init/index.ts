import { normalizeOpeningBalance } from "../../account/opening-balance";
import { AccountReconciliationError, AccountResponseError } from "../../services/account";
import { accountApi } from "../../services/runtime";

Page({
  data: {
    openingBalance: "",
    submitting: false,
    errorMessage: "",
  },

  onOpeningBalanceInput(event: WechatMiniprogram.Input) {
    this.setData({ openingBalance: event.detail.value });
  },

  async onSubmit() {
    if (this.data.submitting) {
      return;
    }
    const openingBalance = normalizeOpeningBalance(this.data.openingBalance);
    if (openingBalance === null) {
      this.setData({ errorMessage: "请输入最多两位小数的起始余额" });
      return;
    }

    this.setData({ submitting: true, errorMessage: "", openingBalance });
    try {
      await accountApi.initializeAccount(openingBalance);
      wx.reLaunch({ url: "/pages/account-home/index" });
    } catch (error) {
      const errorMessage =
        error instanceof AccountReconciliationError || error instanceof AccountResponseError
          ? error.message
          : "初始化失败，请稍后重试";
      this.setData({ submitting: false, errorMessage });
    }
  },
});
