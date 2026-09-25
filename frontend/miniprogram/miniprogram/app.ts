import { resolveEnvironment, type EnvironmentName } from "./config/environment";
import { restoreSession } from "./session/session";
import { authNavigation } from "./services/runtime";
import { wechatStorage } from "./storage/wechat-storage";

interface AppOption {
  globalData: {
    environmentName: EnvironmentName;
  };
}

App<AppOption>({
  globalData: {
    environmentName: "dev",
  },

  onLaunch() {
    const environment = resolveEnvironment();
    this.globalData.environmentName = environment.name;
    if (restoreSession(wechatStorage, new Date()) !== null) {
      authNavigation.enterAuthenticatedHome();
    }
  },
});
