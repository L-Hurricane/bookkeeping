import { authApi, authNavigation } from "../../services/runtime";

Page({
  data: {
    title: "个人记账",
    status: "Frontend Session Ready",
    submitting: false,
    errorMessage: "",
  },

  async onLogout() {
    if (this.data.submitting) {
      return;
    }

    this.setData({ submitting: true, errorMessage: "" });
    try {
      await authApi.logout();
      authNavigation.enterLogin();
    } catch {
      this.setData({ submitting: false, errorMessage: "退出失败，请稍后重试" });
    }
  },
});
