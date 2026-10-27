import { resolveEnvironment } from "../config/environment";
import { createHttpClient } from "../http/client";
import { wechatTransport } from "../http/wechat-transport";
import { createAuthNavigation, type AuthNavigation } from "../navigation/auth-navigation";
import { wechatStorage } from "../storage/wechat-storage";
import { createMonthlyAnalysisApi, type MonthlyAnalysisApi } from "./analysis";
import { createAccountApi, type AccountApi } from "./account";
import { createAuthApi, type AuthApi } from "./auth";
import { createBalanceAdjustmentApi, type BalanceAdjustmentApi } from "./balance-adjustment";
import { createCategoryApi, type CategoryApi } from "./category";
import { createLedgerApi, type LedgerApi } from "./ledger";
import { createLedgerFeedApi, type LedgerFeedApi } from "./ledger-feed";
import { createLedgerAnalysisApi, type LedgerAnalysisApi } from "./ledger-analysis";
import { createLedgerStatisticsApi, type LedgerStatisticsApi } from "./ledger-statistics";
import { createStatisticsApi, type StatisticsApi } from "./statistics";
import { createTagApi, type TagApi } from "./tag";
import { createTransactionApi, type TransactionApi } from "./transaction";
import { createTransferApi, type TransferApi } from "./transfer";

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
export const ledgerFeedApi: LedgerFeedApi = createLedgerFeedApi(http);
export const categoryApi: CategoryApi = createCategoryApi(http);
export const statisticsApi: StatisticsApi = createStatisticsApi(http);
export const ledgerStatisticsApi: LedgerStatisticsApi = createLedgerStatisticsApi(http);
export const ledgerAnalysisApi: LedgerAnalysisApi = createLedgerAnalysisApi(http);
export const monthlyAnalysisApi: MonthlyAnalysisApi = createMonthlyAnalysisApi(http);
export const tagApi: TagApi = createTagApi(http);
export const transactionApi: TransactionApi = createTransactionApi(http);
export const balanceAdjustmentApi: BalanceAdjustmentApi = createBalanceAdjustmentApi(http);
export const transferApi: TransferApi = createTransferApi(http);
