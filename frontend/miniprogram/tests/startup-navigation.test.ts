import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { routeAfterAccountQuery, type Account } from "../miniprogram/account/account";
import { createAuthNavigation } from "../miniprogram/navigation/auth-navigation";
import { appLaunchRedirect, routeBootstrap, routeLoginShow } from "../miniprogram/navigation/startup";

const account: Account = {
  id: 1001,
  name: "微信",
  kind: "NORMAL",
  currency: "CNY",
  openingBalance: "50.00",
  currentBalance: "50.00",
  createdTime: "2026-09-01T10:00:00+08:00",
};

describe("startup navigation", () => {
  it("sends bootstrap without a session to login and does not query accounts", () => {
    assert.equal(routeBootstrap(false), "login");
  });

  it("sends bootstrap with a session to account overview without querying accounts", () => {
    assert.equal(routeBootstrap(true), "home");
    assert.equal(routeAfterAccountQuery([account]).destination, "list");
    assert.equal(routeAfterAccountQuery([]).destination, "init");
  });

  it("sends login with an existing session straight to account overview", () => {
    assert.equal(routeLoginShow(true), "home");
    assert.equal(routeLoginShow(false), "stay");
    const urls: string[] = [];
    const navigation = createAuthNavigation({
      currentRoute: () => "pages/login/index",
      reLaunch(url, done) {
        urls.push(url);
        done();
      },
    });
    navigation.enterAuthenticatedHome();
    assert.deepEqual(urls, ["/pages/account-list/index"]);
  });

  it("does not relaunch from app launch", () => {
    assert.equal(appLaunchRedirect(), null);
  });

  it("keeps authenticated 401 and explicit logout on the login route", () => {
    const urls: string[] = [];
    const navigation = createAuthNavigation({
      currentRoute: () => "pages/account-list/index",
      reLaunch(url, done) {
        urls.push(url);
        done();
      },
    });
    navigation.enterLogin();
    assert.deepEqual(urls, ["/pages/login/index"]);
  });
});
