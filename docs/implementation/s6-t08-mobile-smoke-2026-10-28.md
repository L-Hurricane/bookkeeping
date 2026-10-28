# S6-T08 真机局域网准备 2026-10-28

这不是 T08 PASS。没有代签。没有新产品代码。产品代码仍是 `2aeeca0341ba1dacbe950193c605c916ebc60141`。

- 批准依据是 `docs/implementation/bookkeeping-s6-t08-remaining-authorizations-approval-record-20261027-v0.1.md`。Review 原件 SHA-256 仍是 `b15181c76be0b26b3a912cb7b8476bbfc887508488694f5e9a6a13aba9f8c0f7`。
- 预检时隔离 API 监听 `*:18081`，本机防火墙状态是关闭。`192.168.1.3:18081/actuator/health` 返回 200。未登录访问统计接口返回 401。日常 `8080` 仍是 PID `775`。`pfctl` 不能读取包过滤规则。
- 因此不能证明局域网来源限制，也不能证明没有公网转发。没有打开新端口，没有隧道，没有关闭防火墙，没有把小程序地址改成局域网 IP，没有生成扫码入口。
- 已将隔离服务改回只监听 `127.0.0.1:18081`，PID `88759`。本机健康检查为 UP。`192.168.1.3:18081` 连接被拒绝。库 `bk_s6_family_e2e_20261027` Flyway 18，up to date。重启后 `s6_family_auto` 与 `s6_family_owner` 仍各 6295 笔交易。没有写试用用户，没有调用模型。
- 真机 NOT TESTED。小程序 `urlCheck` 仍为 true，dev/test 地址仍是 `http://127.0.0.1:18081`。手机扫码现在不能访问这台合成服务。
