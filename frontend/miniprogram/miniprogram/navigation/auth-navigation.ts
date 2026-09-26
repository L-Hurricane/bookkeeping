const LOGIN_ROUTE = "pages/login/index";
const LOGIN_URL = "/pages/login/index";
const AUTHENTICATED_HOME_ROUTE = "pages/account-home/index";
const AUTHENTICATED_HOME_URL = "/pages/account-home/index";

export interface AuthNavigationDependencies {
  currentRoute: () => string | undefined;
  reLaunch: (url: string, done: () => void) => void;
}

export interface AuthNavigation {
  enterLogin(): void;
  enterAuthenticatedHome(): void;
}

export function createAuthNavigation(dependencies: AuthNavigationDependencies): AuthNavigation {
  let pending: "login" | "home" | null = null;

  function start(target: "login" | "home", route: string, url: string): void {
    if (pending === target || dependencies.currentRoute() === route) {
      return;
    }
    pending = target;
    dependencies.reLaunch(url, () => {
      if (pending === target) {
        pending = null;
      }
    });
  }

  return {
    enterLogin() {
      start("login", LOGIN_ROUTE, LOGIN_URL);
    },
    enterAuthenticatedHome() {
      start("home", AUTHENTICATED_HOME_ROUTE, AUTHENTICATED_HOME_URL);
    },
  };
}
