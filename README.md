# Bookkeeping v0.1 — Phase 0

当前仅包含 Spring Boot 工程骨架、MySQL/Flyway 接入和 Health Check。
正式基线位于 `docs/`；完成 Gate 1 人工 Review 前不进入 Phase 1。

## 工程基线

- Java 25 LTS、Spring Boot 4.1.1、MySQL 8.4 LTS。
- `./mvnw` 固定 Apache Maven 3.9.16；官方 Maven Wrapper 3.3.4
  使用 only-script 分发，首次运行需要网络，Maven 分发包启用 SHA-256 校验。
- 依赖版本由 Spring Boot 管理；无全局 Maven、Docker 或 H2 前置要求。
- 坐标：`com.limingyuan:bookkeeping`；base package：`com.limingyuan.bookkeeping`。

## 本地准备

macOS / Homebrew 可安装 `openjdk@25` 和 `mysql@8.4`。例如：

```sh
brew install openjdk@25 mysql@8.4
export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$(brew --prefix mysql@8.4)/bin:$PATH"
java -version
./mvnw --version
```

启动本机 MySQL 8.4 后，由数据库管理员创建两个独立空库：

```sql
CREATE DATABASE bookkeeping CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE bookkeeping_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

分别创建开发账号和测试账号，权限仅限各自数据库（包含 Flyway 所需 DDL 权限）。
不要让测试账号访问开发库或真实数据；不要使用 root 运行应用。
密码通过交互式输入或安全环境配置设置，不写入版本管理或命令历史。
这里不提供固定密码、用户数据或业务表 SQL。

## 配置与启动

默认 profile 为 `local`；使用 `SPRING_PROFILES_ACTIVE` 显式选择运行环境。
公共配置要求外部提供 DB 连接信息，`local` 仅为 URL 提供本机开发库默认值。

- `DB_URL`：默认 `jdbc:mysql://localhost:3306/bookkeeping`（仅 local）。
- `DB_USERNAME` / `DB_PASSWORD`：必须外部提供，没有默认密码。
- `SPRING_PROFILES_ACTIVE`：例如 `local`。
- `SERVER_PORT`：可选，默认 8080。

先在当前 shell 安全注入凭证，然后运行：

```sh
export SPRING_PROFILES_ACTIVE=local
./mvnw spring-boot:run
```

`.env` 文件不会被 Spring Boot 自动加载。切勿将凭证放入 JDBC URL。
远程数据库应配置合适的 TLS；工程不默认关闭 TLS 或启用公钥获取。

另一个终端检查：

```sh
curl --fail http://localhost:8080/actuator/health
```

预期为 `{"status":"UP"}`。Health 包含数据库健康检查，不公开组件和连接详情。
只有 `GET /actuator/health` 匿名开放，其余请求在 Phase 0 拒绝；没有登录页、
HTTP Basic、默认生成用户或业务认证实现。此临时边界在 Phase 1 Review 时调整。

## 测试与打包

测试自动激活 `test` profile，使用相同 MySQL、Flyway、JPA、Security 配置链路。
测试配置仅位于 `src/test/resources`，不会进入运行 JAR。

- `TEST_DB_URL`：默认 `jdbc:mysql://localhost:3306/bookkeeping_test`。
- `TEST_DB_USERNAME` / `TEST_DB_PASSWORD`：必须提供独立测试账号凭证。
- 测试数据库名称必须为 `bookkeeping_test`，可以覆盖连接的主机或端口。

准备好测试环境变量后：

```sh
./mvnw test
./mvnw package
```

测试覆盖 Context / test profile、真实 MySQL 8.4 连接、字符集/排序规则、
JdbcClient、JPA 无业务实体、Flyway 空迁移目录及历史表、公开 Health 和其余路由拒绝。
未设置凭证或 MySQL 未就绪时测试应失败，不自动跳过、不使用 H2 或模拟数据库。
测试仅查询并允许 Flyway 初始化历史表，不清库、不创建业务表。

打包输出为 `target/bookkeeping-0.1.0-SNAPSHOT.jar`，配置好开发环境变量后可运行：

```sh
java -jar target/bookkeeping-0.1.0-SNAPSHOT.jar
```

## Flyway 与包边界

`src/main/resources/db/migration` 暂无 SQL，Flyway 会初始化技术历史表，
无需 marker migration。`baseline-on-migrate=false` 防止自动接管非空库；
`clean-disabled=true` 禁止清库；Hibernate 仅 `validate`，SQL init 禁用。
`V1` 留待 Phase 1 的 users migration，当前不生成正式业务 Migration。

顶层包：`identity`、`account`、`category`、`tag`、`transaction`、`adjustment`、
`ledger`、`statistics`、`common`。仅以 `package-info.java` 保留业务边界，
公共包中只有 Phase 0 的技术安全配置，没有空业务分层或业务类。

## Gate 1

人工 Review：工程坐标与版本、`pom.xml` 依赖、local/test 配置、临时 Health
访问边界、package-by-feature 骨架、Flyway 空库策略及实际测试结果。
正式 Product / Domain / Architecture / Data Model / API 基线未修改。
