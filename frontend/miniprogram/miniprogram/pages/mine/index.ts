import { LOGOUT_FAILURE_MESSAGE } from "../../account/logout-view";
import { accountArchivePath } from "../../account/navigation";
import { authApi, authNavigation } from "../../services/runtime";

Page({
  data: {
    loggingOut: false,
    logoutErrorMessage: "",
  },

  onOpenCategories() {
    wx.navigateTo({ url: "/pages/category-manage/index" });
  },

  onOpenTags() {
    wx.navigateTo({ url: "/pages/tag-manage/index" });
  },

  onOpenArchive() {
    wx.navigateTo({ url: accountArchivePath() });
  },

  async onLogout() {
    if (this.data.loggingOut) return;
    this.setData({ loggingOut: true, logoutErrorMessage: "" });
    try {
      await authApi.logout();
      authNavigation.enterLogin();
    } catch {
      this.setData({ loggingOut: false, logoutErrorMessage: LOGOUT_FAILURE_MESSAGE });
    }
  },
});
