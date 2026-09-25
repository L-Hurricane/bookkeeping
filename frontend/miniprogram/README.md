# 个人记账 · 微信小程序

Phase 0A 只完成工程 Bootstrap。页面能启动，并显示「个人记账 / Frontend Bootstrap Ready」。

Backend 联调尚未开始。这里没有 Login、Account、Ledger、Transaction 或任何真实 HTTP 调用。

## 技术栈

- 微信原生小程序
- TypeScript
- 不使用 Taro、uni-app、React、Vue

设计基线：`docs/frontend/v0.1-frontend-design.md`。

小程序位于本仓库的 `frontend/miniprogram/`。Backend 仍在仓库根目录，没有被移动。

## 环境要求

- Node.js 22 或更新的 Current/LTS，自带 npm
- 微信开发者工具，用于导入运行

本机在 Phase 0A 创建时没有检测到 `node` / `npm`。下面的命令是 `package.json` 里定义的脚本，当时没有执行，不能当作已经通过。

## 安装依赖

在 `frontend/miniprogram/`：

```sh
npm install
```

## 检查

```sh
npm run typecheck
npm run lint
npm test
```

`typecheck` 跑两份 TypeScript 配置：小程序源码，以及环境配置测试。`test` 只覆盖 `resolveEnvironment`。

## 微信开发者工具

1. 打开微信开发者工具。
2. 导入项目目录：本仓库的 `frontend/miniprogram/`（包含 `project.config.json` 的那一层）。
3. AppID 使用项目里的 `touristappid`（游客模式）。真正的微信 AppID 仍需要人来配置，不要把真实 AppID 写进仓库。
4. 编译后应打开 Bootstrap 页，看到「个人记账」和「Frontend Bootstrap Ready」。

`project.private.config.json` 是开发者工具的本机配置，已在根 `.gitignore` 中忽略。

## 当前不做

- 不访问 Backend
- 不保存 Session 或 Token
- 不读取 Account
- 生产环境的 API Host 留空，不在仓库里写真实生产域名
