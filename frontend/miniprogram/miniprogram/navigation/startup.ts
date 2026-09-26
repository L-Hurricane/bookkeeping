export type BootstrapEntry = "login" | "home";
export type LoginShowEntry = "stay" | "home";

/** Bootstrap only chooses Login or Account Home. App launch does not choose a page. */
export function routeBootstrap(hasValidSession: boolean): BootstrapEntry {
  return hasValidSession ? "home" : "login";
}

export function routeLoginShow(hasValidSession: boolean): LoginShowEntry {
  return hasValidSession ? "home" : "stay";
}

export function appLaunchRedirect(): null {
  return null;
}
