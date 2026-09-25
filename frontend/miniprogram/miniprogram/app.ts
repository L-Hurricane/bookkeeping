import { resolveEnvironment, type EnvironmentName } from "./config/environment";

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
  },
});
