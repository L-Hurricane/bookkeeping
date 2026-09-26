import { routeBootstrap } from "../../navigation/startup";
import { restoreSession } from "../../session/session";
import { authNavigation } from "../../services/runtime";
import { wechatStorage } from "../../storage/wechat-storage";

Page({
  onShow() {
    if (routeBootstrap(restoreSession(wechatStorage, new Date()) !== null) === "login") {
      authNavigation.enterLogin();
      return;
    }
    authNavigation.enterAuthenticatedHome();
  },
});
