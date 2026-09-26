export const LOGOUT_FAILURE_MESSAGE = "退出失败，请稍后重试";

export type LogoutResume = "account" | "ledger" | null;

export function resumeAfterLogoutFailure(phase: string): LogoutResume {
  if (phase === "account-loading") {
    return "account";
  }
  if (phase === "ledger-loading") {
    return "ledger";
  }
  return null;
}

export function selectedMonthDuringLogout(loggingOut: boolean, currentMonth: string, nextMonth: string): string {
  return loggingOut ? currentMonth : nextMonth;
}

export function logoutFailureView<T extends { readonly phase: string }>(view: T): T & {
  loggingOut: false;
  logoutErrorMessage: string;
} {
  return { ...view, loggingOut: false, logoutErrorMessage: LOGOUT_FAILURE_MESSAGE };
}
