export type BootstrapEntry = "login" | "query-accounts";
export type LoginShowEntry = "stay" | "bootstrap";

/** Bootstrap owns session routing. App launch does not choose a page. */
export function routeBootstrap(hasValidSession: boolean): BootstrapEntry {
  return hasValidSession ? "query-accounts" : "login";
}

export function routeLoginShow(hasValidSession: boolean): LoginShowEntry {
  return hasValidSession ? "bootstrap" : "stay";
}

export function appLaunchRedirect(): null {
  return null;
}
