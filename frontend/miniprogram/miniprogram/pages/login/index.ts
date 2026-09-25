import { ApiError } from "../../http/client";
import { routeLoginShow } from "../../navigation/startup";
import { restoreSession, saveSession } from "../../session/session";
import { authApi, authNavigation } from "../../services/runtime";
import { wechatStorage } from "../../storage/wechat-storage";

Page({
  data: {
    username: "",
    password: "",
    submitting: false,
    errorMessage: "",
  },

  onShow() {
    if (routeLoginShow(restoreSession(wechatStorage, new Date()) !== null) === "bootstrap") {
      authNavigation.enterAuthenticatedHome();
    }
  },

  onUsernameInput(event: WechatMiniprogram.Input) {
    this.setData({ username: event.detail.value });
  },

  onPasswordInput(event: WechatMiniprogram.Input) {
    this.setData({ password: event.detail.value });
  },

  async onSubmit() {
    if (this.data.submitting) {
      return;
    }

    const username = this.data.username.trim();
    const password = this.data.password;
    if (username.length === 0) {
      this.setData({ errorMessage: "请输入用户名" });
      return;
    }
    if (password.length === 0) {
      this.setData({ errorMessage: "请输入密码" });
      return;
    }

    this.setData({ submitting: true, errorMessage: "" });
    try {
      const result = await authApi.login(username, password);
      saveSession(wechatStorage, { token: result.token, expiresAt: result.expiresAt });
      authNavigation.enterAuthenticatedHome();
    } catch (error) {
      const errorMessage =
        error instanceof ApiError && error.code === "AUTHENTICATION_FAILED"
          ? "用户名或密码错误"
          : "登录失败，请稍后重试";
      this.setData({ submitting: false, errorMessage });
    }
  },
});
