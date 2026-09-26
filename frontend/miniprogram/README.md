# 个人记账 · 微信小程序

Phase 0D 在账户首页读取只读月度流水。可以切换自然月，查看 Backend 返回的当前余额、月度总计和流水。记一笔、流水详情、余额调整操作和独立统计页还没有开始。

设计基线：`docs/frontend/v0.1-frontend-design.md`。

小程序位于本仓库的 `frontend/miniprogram/`。Backend 仍在仓库根目录。

## 技术栈

- 微信原生小程序
- TypeScript
- 不使用 Taro、uni-app、React、Vue、axios

## 环境要求

- Node.js 22 或更新版本，自带 npm
- 微信开发者工具
- 本机 Backend 与 MySQL，仅在做登录联调时需要

## 安装与检查

在 `frontend/miniprogram/`：

```sh
npm install
npm run typecheck
npm run lint
npm test
```

## 本地联调

1. 按仓库根目录 `README.md` 准备 MySQL、`DB_USERNAME` / `DB_PASSWORD`，以及预创建用户所需的 Bootstrap 环境变量。
2. 在仓库根目录启动 Backend：

```sh
export SPRING_PROFILES_ACTIVE=local
./mvnw spring-boot:run
```

3. 另一个终端确认：

```sh
curl --fail http://127.0.0.1:8080/actuator/health
```

预期为 `{"status":"UP"}`。

4. 用微信开发者工具导入 `frontend/miniprogram/`。
5. 本地 Backend 是 `http://127.0.0.1:8080`。共享的 `project.config.json` 保持 `urlCheck: true`。联调时在开发者工具打开「详情 → 本地设置」，勾选「不校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书」。这个选择写在本机的 `project.private.config.json`，该文件已忽略，不要提交。
6. 编译后先进入 Bootstrap。没有有效 Session 时进入登录页。输入已预创建的用户名和密码。成功后 Bootstrap 会读取账户。尚未初始化时进入「初始化资金账户」，只填写起始余额，例如 `0`、`100.00` 或 `-100.50`，然后点「开始记账」。成功后进入账户首页，账户名和当前余额来自 Backend。
7. 已经初始化过时，登录后应直接进入账户首页，而不是再填起始余额。
8. 用错误密码登录，页面显示「用户名或密码错误」，用户名仍留在输入框中。
9. 在账户首页点「退出登录」，回到登录页。
10. 再次登录成功后，关闭并重新编译小程序，或使用「返回首页」。本地 Session 未过期时，Bootstrap 应回到账户首页，不要求重新登录。断网时 Bootstrap 应停留在错误状态并可以重新加载，不应进入初始化页。退出登录后再进入，应回到登录页。

密码和 Token 不会写入源码，也不会打日志。生产 API Host 仍为空。

## 当前不做

- 记一笔、流水详情、流水新增/编辑/删除
- 余额调整的新增/删除
- 独立统计页、分类管理、标签管理
- Refresh Token、JWT、微信登录、注册、找回密码
