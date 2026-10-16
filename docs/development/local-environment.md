# 本地开发环境

## 项目要求

后端需要 Java 25 和 MySQL 8.4。这是版本要求，不是安装路径要求。

开发库、普通测试库和迁移测试库必须分开：

- `DB_URL` 指向开发库 `bookkeeping`。
- `TEST_DB_URL` 指向 `bookkeeping_test`。主机和端口可以变，库名必须是 `bookkeeping_test`。
- `MIGRATION_TEST_DB_URL` 指向 `bookkeeping_migration_test`。迁移测试会清空这个库。

三者都不得指向对方，也不得指向个人账本数据库。

私有配置留在本机。文档只记录变量名和路径，不记录密码、API Key 或其他真实凭证。

## 新成员首次准备

新成员可以用适合自己机器的方式安装 Java 25 和 MySQL 8.4，包括发行版包、官方安装包或其他已有安装。安装结果必须满足上面的版本、三个库的隔离，以及仓库里的运行契约。

仓库 README 里的 Homebrew 示例只是一种准备方式，不是本项目规定的唯一安装路径。

准备好三个空库和各自账号后，在当前 shell 注入凭证再执行测试。Spring Boot 不会自动读取 `.env`。不要把凭证写进 JDBC URL、仓库或 Git。

未设置测试凭证或 MySQL 未就绪时，测试应当失败。不要改成 H2，也不要跳过测试。

## 当前开发机

这台机器已经用独立 Bootstrap 安装 Java 和 MySQL，不通过 Homebrew 管理这两项。恢复这台机器时复用现有安装。路径使用 `$HOME`，不要写死个人用户名。

Java 25，Zulu：

`$HOME/.local/share/bookkeeping-bootstrap/zulu25.36.205-ca-jdk25.0.4.1-macosx_aarch64/Contents/Home`

MySQL 8.4 程序目录：

`$HOME/.local/share/bookkeeping-bootstrap/mysql-8.4.3-macos14-arm64`

`mysqld`、`mysql` 和 `mysqladmin` 都在该目录的 `bin` 下。

数据目录：

`$HOME/.local/share/bookkeeping-bootstrap/data`

服务配置：

`$HOME/.config/bookkeeping/mysql.cnf`

管理连接配置：

`$HOME/.config/bookkeeping/mysql-admin.cnf`

监听地址是 `127.0.0.1:3306`。

应用和测试变量在：

`$HOME/.config/bookkeeping/bootstrap.env`

常用变量名：`JAVA_HOME`、`DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`TEST_DB_URL`、`TEST_DB_USERNAME`、`TEST_DB_PASSWORD`、`MIGRATION_TEST_DB_URL`。

这个文件留在本机。不要打印内容，不要复制进仓库，不要提交 Git。

在当前 shell 中加载，并让子进程继承：

```sh
set -a
source "$HOME/.config/bookkeeping/bootstrap.env"
set +a
```

### 恢复已有 MySQL

按这个顺序检查。缺程序、配置或数据目录时停止，不要改数据目录或配置：

1. 确认上面的 `mysqld`、`mysql.cnf`、`mysql-admin.cnf` 和数据目录都存在。
2. 确认 `127.0.0.1:3306` 是否已经有进程监听。
3. 用现有管理配置看服务状态：

```sh
"$HOME/.local/share/bookkeeping-bootstrap/mysql-8.4.3-macos14-arm64/bin/mysqladmin" \
  --defaults-file="$HOME/.config/bookkeeping/mysql-admin.cnf" ping
```

已经在运行就直接复用，不要再启动一次。

未运行，且程序、配置、数据目录和端口检查都正常时，用现有安装启动：

```sh
"$HOME/.local/share/bookkeeping-bootstrap/mysql-8.4.3-macos14-arm64/bin/mysqld" \
  --defaults-file="$HOME/.config/bookkeeping/mysql.cnf" --daemonize
```

然后重复 `mysqladmin ping`，直到返回服务可用，再用 `mysqladmin status` 看运行状态。

这台机器的测试使用上面的 Bootstrap JDK，不改全局 Java：

```sh
export JAVA_HOME="$HOME/.local/share/bookkeeping-bootstrap/zulu25.36.205-ca-jdk25.0.4.1-macosx_aarch64/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
cd backend
./mvnw -version
```

这些 `cd backend` 都从仓库根目录进入。

## 运行数据库集成测试之前

同时核对两个地址，缺一不可：

- `TEST_DB_URL` 的库名是 `bookkeeping_test`。
- `MIGRATION_TEST_DB_URL` 的库名是 `bookkeeping_migration_test`。

任何一个指向 `bookkeeping` 或个人账本数据库时，停止，不要运行会写入或清理数据的测试。

Spring 测试使用 `backend/src/test/resources/application-test.yml` 的 `test` profile，数据源来自 `TEST_DB_*`。`BootstrapIntegrationTest` 会核对当前库名是 `bookkeeping_test`。

`MigrationIntegrationTest` 会清空迁移库。清空前，`MigrationSchemaGuard` 先拒绝库名不是 `bookkeeping_migration_test` 的地址；连接成功后还会再核对当前库名。不要绕过这个保护。

API 集成测试只清理自己创建的测试用户。不要用测试账号连接开发库。

先加载本机凭证，再执行：

```sh
cd backend
./mvnw test
```

在 `backend/` 执行 `./mvnw test` 不调用真实 DashScope。不要为了跑测试设置会打到真实模型的开关。

## 本地月度分析 Mock

默认关闭。普通 `local` 启动仍使用 Qwen generator。没有配置 Provider 时，生成返回 502 `GENERATION_FAILED`，不会自动改成 Mock。

只有这些条件同时成立时，Mock generator 才会装配：

- 有效 profile 包含 `local`，并且不包含 `prod` 或 `production`。没有显式 profile 时，默认 profile `local` 算满足。
- `BOOKKEEPING_ANALYSIS_MOCK_ENABLED=true`。
- `BOOKKEEPING_ANALYSIS_MOCK_USERS` 是逗号分隔的用户名白名单。用户名留在本机环境，不写进仓库。
- `DASHSCOPE_API_KEY` 为空。

白名单之外的用户在配额预留之前被拒绝，不会产生新的 Generation 或 Snapshot。已有成功 Snapshot 的复用规则不变。

冲突配置会让进程启动失败，错误信息里没有密钥。Mock 不会在失败后退回真实 Qwen，也不会发出外部请求。

成功 Snapshot 的 provider 是 `local-mock`，model 是 `deterministic-acceptance`。关闭 Mock 后，同一用户、同一月份、同一指纹仍会复用这份 Snapshot。不要在启动时自动删除它。

`BOOKKEEPING_ANALYSIS_MOCK_MODE` 只接受 `success` 或 `failure`，默认 `success`。`failure` 只用于验证失败路径。

## 前端

在 `frontend/miniprogram`：

```sh
npm test
npm run typecheck
npm run lint
```

## 禁止操作

- 清空、重建开发库 `bookkeeping`，或把测试指向它。
- 重置数据库密码，或修改现有账号权限。
- 在这台机器上执行 `mysqld --initialize`、新建数据目录，或覆盖已有 `mysql.cnf`。
- 把 `bootstrap.env`、`mysql-admin.cnf` 或其他含凭证的文件提交进 Git。
