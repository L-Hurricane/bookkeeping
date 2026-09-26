import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { createAuthNavigation } from "../miniprogram/navigation/auth-navigation";

function harness(initialRoute?: string) {
  let route = initialRoute;
  const launched: string[] = [];
  const finish: Array<() => void> = [];
  const navigation = createAuthNavigation({
    currentRoute: () => route,
    reLaunch(url, done) {
      launched.push(url);
      finish.push(() => {
        route = url.replace(/^\//, "");
        done();
      });
    },
  });
  return { navigation, launched, finish };
}

describe("auth navigation", () => {
  it("sends one login transition while another is in flight", () => {
    const { navigation, launched } = harness("pages/bootstrap/index");
    navigation.enterLogin();
    navigation.enterLogin();
    assert.deepEqual(launched, ["/pages/login/index"]);
  });

  it("does not reLaunch login when that route is already current", () => {
    const { navigation, launched } = harness("pages/login/index");
    navigation.enterLogin();
    assert.deepEqual(launched, []);
  });

  it("allows a later login transition after the first one settles", () => {
    const { navigation, launched, finish } = harness("pages/bootstrap/index");
    navigation.enterLogin();
    finish[0]?.();
    navigation.enterAuthenticatedHome();
    finish[1]?.();
    navigation.enterLogin();
    assert.deepEqual(launched, ["/pages/login/index", "/pages/account-home/index", "/pages/login/index"]);
  });
});
