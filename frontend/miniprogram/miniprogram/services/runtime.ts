import { resolveEnvironment } from "../config/environment";
import { createHttpClient } from "../http/client";
import { wechatTransport } from "../http/wechat-transport";
import { createAuthNavigation, type AuthNavigation } from "../navigation/auth-navigation";
import { wechatStorage } from "../storage/wechat-storage";
import { createAccountApi, type AccountApi } from "./account";
import { createAuthApi, type AuthApi } from "./auth";
import { createCategoryApi, type CategoryApi } from "./category";
import { createLedgerApi, type LedgerApi } from "./ledger";
import { createTagApi, type TagApi } from "./tag";
import { createTransactionApi, type TransactionApi } from "./transaction";

export const authNavigation: AuthNavigation = createAuthNavigation({
  currentRoute() {
    const pages = getCurrentPages();
    const current = pages[pages.length - 1];
    return current?.route;
  },
  reLaunch(url, done) {
    wx.reLaunch({ url, complete: done });
  },
});

const http = createHttpClient({
  baseUrl: resolveEnvironment().apiBaseUrl,
  transport: wechatTransport,
  storage: wechatStorage,
  now: () => new Date(),
  onUnauthorized: () => {
    authNavigation.enterLogin();
  },
});

export const authApi: AuthApi = createAuthApi(http, wechatStorage);
export const accountApi: AccountApi = createAccountApi(http);
export const ledgerApi: LedgerApi = createLedgerApi(http);
export const categoryApi: CategoryApi = createCategoryApi(http);
export const tagApi: TagApi = createTagApi(http);
export const transactionApi: TransactionApi = createTransactionApi(http);
