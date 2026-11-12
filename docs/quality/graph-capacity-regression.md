# 图执行并发治理回归与交叉审查

日期：2026-11-12。范围：OSS LangGraph 图执行组件、Java V67/V68 治理仓储、认证派发宿主和账户管理；不是生产吞吐报告，不调用方舟，不修改业务数据库。

## 修订的执行契约

短执行片段在安全调用边界按次数/单调时间预算让出；长等待写入 interrupt 后返回。执行线程、Worker 槽位、数据库连接、外呼许可分别计量。在途 HTTP 不凭 Checkpoint 或本地超时安全交接；没有远端任务 ID 的请求仍占调用额度。正常 resume 不增加 Attempt。

## 回归矩阵

| 风险 | 证据 |
| --- | --- |
| 双实例同时领取最后一个平台槽位 | 两个 JDBC store/事务竞争，只一个取得资格；另一方空返回 |
| 大租户占满或行锁阻塞 | 租户硬上限与持久轮转；另一个可运行租户获得下一次资格，不被锁住的队头永久阻塞 |
| 挂起导致 outstanding 漏计 | defer 后仍占接管准入；撤执行权但业务未终结仍计数；同 Run 幂等 reserve 不重复占位 |
| 共享账户多个资源部分占用 | 许可一次锁定检查全部资源；不足不写任何分配，双线程争抢只有一个成功 |
| HTTP 超时或旧 Worker 死亡后额度立刻释放 | ACTIVE/QUARANTINED 均计数；代次变化/孤儿扫描只隔离；迟到结算只影响原 token |
| 一个账户异常拖住独立账户 | 故障账户新请求返回持久等待，独立账户可用额度仍可调用；不宣称全局额度耗尽时还能无限保障 |
| 缓存恢复被新配额阻塞 | 模型原响应/工具成功账本只读恢复独立于新外呼许可；重新授权并校验引用后关联结算 |
| 配额绕过或参数变化 | 缺资源策略拒绝；同操作改变参数摘要拒绝；租户 RLS 无权读取全局治理表 |
| 连续预算让出与重启 | 单片一次调用，五个片段完成原五个操作，无额外模型/工具执行；恢复使用原待执行意图 |
| 耗时超过协作阈值 | 已提交调用完成并保存回执后才让出，不在 HTTP 中途制造“可接管”状态 |
| Worker 预取/连接泄漏 | slots=1 的阻塞 RPC 期间不再领取；持久等待先关闭检查点上下文再交接；失去租约阻止后续调用 |
| defer ACK 丢失 | 重新领取旧命令时读取最新 interrupt，而不是强制使用旧恢复游标；不重新执行已持久动作 |
| 原生路径/终态重试绕过准入 | V68 AFTER 触发器覆盖两种后端；终态释放、重试重新申请，冲突 no-op/事务回滚不改变计数；并发最后一位只一成功 |
| 升级漏计/租户越权 | 迁移锁住 Run 写入直至建触发器；非超级用户 FORCE RLS 基线回填；普通角色可经触发器记账但看不到全局计数 |
| 两个 Provider 放大同一账户额度 | 跨租户不同 Provider 归并同一资源；在途时换绑被拒，失败配置/审计一同回滚；MCP 仅反查租户已发布快照 |
| Cookie/服务令牌越权派发 | Spring 真实安全过滤链 + MockMvc 验证 Worker 独立凭据、HTTPS/大小/绑定校验、未知路径拒绝；普通管理员 Cookie 不产生派发权限 |
| 账户管理越权/CSRF | 平台角色及 CSRF 双门禁，其他角色不可修改；成功配置有不可变 revision 审计 |
| HTTP 控制链路 | Python 真实本机 HTTP 验证 claim/renew/批量续租/defer/ack、空队列、错误凭据/异 artifact/异 Worker、跨 Run 观察阻断 |

运行命令：

```powershell
./scripts/test-langgraph-runtime.ps1 -PostgresBin 'C:/Program Files/PostgreSQL/18/bin' -IncludeJava
./mvnw.cmd -o -pl agent-engine -am '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest=PlatformAgentExecutorTest,AgentRunWorkerServiceImplTest,DefaultAgentResultPolicyTest,GraphToolGatewayTest,AgentToolRegistryTest' test
```

最终联合回归 **63 项通过，0 跳过**（23 图语义、24 Gateway HTTP、5 Dispatch HTTP、6 PostgreSQL checkpoint、4 Worker、1 Java 启动）；内嵌 Java **79 项通过，0 跳过**（43 JDBC 持久/治理、19工具 Gateway、12 Registry、1跨语言工具、4架构），不重复累加。另行执行 Spring 宿主装配/安全链/异常 **23 项**、原生相关 **66 项**，均通过（其中 Registry/Gateway 与内嵌集合重叠）。Ruff、格式、Java Checkstyle 和 `git diff --check` 通过。依赖仍有 Python 3.14 弃用提示及 Mockito 动态 agent 提示，未屏蔽。

实际 V65–V68 在独立 PostgreSQL schema 上执行，之前平台表使用最小夹具，不冒充全量 Flyway 升级。Spring 宿主使用真实安全链与 MockMvc、仓储 mock，另以应用上下文验证真实适配器装配及默认关闭/配置失败；Python dispatch 使用真实 HTTP 测试服务。既有 Java/Python Gateway 联调仍保留，但不能把分层组合测试说成完整生产宿主→数据库→Worker→Flowable 端到端。

## 本轮交叉审查修复

1. **未知额度不能阻塞已有回执恢复**：增加独立只读 replay，模型/工具绝不在 replay 分支发外部请求；确认回执后结算，撤权仍拒绝。
2. **快照持久化不等于已释放连接**：Worker 退出检查点会话后才提交 defer/ack，避免后继者领取后与旧会话争锁。
3. **丢交接 ACK 不能信任旧命令游标**：以最新图 interrupt 作恢复依据；不以平台数据决定下一图节点。
4. **轮转不能卡死在锁住的租户**：领取无可锁定行时也推进轮转；下一次有限扫描服务其他租户。
5. **全局计数不能运行在租户过滤视图中**：治理入口要求受信 worker 权限；表强制 RLS，无浏览器 admin 旁路。
6. **恢复错误保持类型化**：工具 replay 与正常调用共用引用校验和异常分类，撤权/超时不退化为通用 500。
7. **账户换绑与领取竞争**：将可信映射解析移入许可分配的策略锁内；否则解析旧账户后再换绑可绕过保护。失败配置不能留新资源或成功审计。
8. **计数升级既要防并发也要防 RLS 隐藏**：回填与触发器同事务锁表；Flyway 独立连接显式使用事务局部可信上下文，不依赖应用 ThreadLocal，提交后恢复。
9. **控制 RPC 不能被预取/逐个续租拖垮**：CLI 每轮至多领取一条，批量续租、有限条数和请求预算；失败按任务失租，不假取消已经发出的请求。
10. **认证过滤器不重复执行**：关闭业务认证 Filter 的 Servlet 自动注册，仅在业务安全链运行，避免内部请求又被 Cookie/服务令牌过滤器消费或拒绝。内部路径白名单之外默认拒绝。
11. **服务主动关闭连接仍须执行读预算**：HTTP/1.0 或 `Connection: close` 时保留 socket 到有界响应读取结束，再统一关闭，不能在头部处理后提前丢失 body reader 的 socket。

## 尚未完成的发布条件

- 全平台 outstanding 已由 V68 治理；原生活跃执行与物理外呼并未自动进入 V67 图许可，不能混淆。
- 认证派发宿主/CLI、artifact 领取、账户管理和变更审计已实现，默认关闭图宿主；业务后端发布路由、最终业务接纳、全量升级及部署级端到端仍为门禁。
- 未知请求可信对账运维入口及所有配额统一版本管理仍开放；不能允许用户提交任意“已完成”证明释放额度。
- 当前是同步有界 Worker/外呼包络，嵌套模型/Embedding/Rerank 精确计费、异步 HTTP、加权公平与管理预留尚待完成。
- 未进行持续负载、网络分区和多进程资源/P95/P99 测量；数据库连接数上限必须与 slots 一起部署，不将机制测试作为吞吐成绩。
- 未切换业务 AgentVersion、调用付费模型或重新推进 Flowable；P-17/压缩/四格式既有基线保持原状态，P1-3/P1-6 不关闭。
