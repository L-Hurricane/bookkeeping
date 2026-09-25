import type { HttpTransport } from "./client";

export const wechatTransport: HttpTransport = (request) =>
  new Promise((resolve, reject) => {
    wx.request({
      url: request.url,
      method: request.method,
      header: request.header,
      data: request.data,
      success(result) {
        resolve({ statusCode: result.statusCode, data: result.data });
      },
      fail() {
        reject(new Error("network"));
      },
    });
  });
