import type { KeyValueStorage } from "./key-value";

export const wechatStorage: KeyValueStorage = {
  getItem(key) {
    try {
      const value = wx.getStorageSync(key) as unknown;
      return typeof value === "string" && value.length > 0 ? value : undefined;
    } catch {
      return undefined;
    }
  },

  setItem(key, value) {
    wx.setStorageSync(key, value);
  },

  removeItem(key) {
    wx.removeStorageSync(key);
  },
};
