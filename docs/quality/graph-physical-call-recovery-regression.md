# 物理外呼生命周期与容量恢复（V75）

日期：2026-11-20。修复 V74 审查发现的容量泄漏：外呼前的 PLAN_REQUIRED/CORRECTION_REQUIRED 若遇到宕机，后继请求会被遗留许可阻塞；模型已经返回但动作校验失败时，许可被误判为未知。根因是把业务成功回执作为物理外呼结束的依据。此修订不按异常代码增加释放白名单。

## 持久事实与发送边界

V75 为许可增加不可变 tracking_version。历史许可默认 0；只有接入生命周期钩子的调用才能创建版本 1。新增不可变 dispatch/outcome 表，身份绑定精确 permit token、租户和 Run；v2 dispatch 还验证同 operation/generation 的预算 call_attempt。表强制 RLS，仅受信 system worker 可访问。

发送前事务锁住 Run 与许可，检查执行权、请求摘要、代次、预算预留及 ACTIVE 状态，再提交 dispatch。释放许可是永久发送屏障，不能重新打开或在其下补发。事务期间不执行模型/工具网络调用。

模型 adapter.invoke / 工具 execute 同步返回后，先持久化 RETURNED 并释放原 token，再执行输出校验、回执、审计或业务裁决。因此，业务拒绝、撤权、停止不再抹去物理返回事实。迟到返回只能结算自己的 token，不能释放另一调用。

| 事实 | 自动处理 | 预算 |
| --- | --- | --- |
| 新许可未记录 dispatch，本地调用已退出 | 同事务记录 NOT_DISPATCHED 并释放，阻止未来发送 | 已预留预算不退款 |
| 新许可未记录 dispatch，已换代、失租或持久停止 | 请求恢复或后台扫描在锁下确认，记录 NOT_DISPATCHED 并释放 | 不退款 |
| 新许可已记录 RETURNED | 精确释放原 token，与业务成功无关 | 保留实际调用记账 |
| 已记录 dispatch，没有返回证据 | 隔离并继续计入容量 | 保留扣账 |
| 历史 tracking_version=0，没有新事实 | 保留旧版保守恢复语义，不能据此断言未发送 | 不改变历史预算 |

同代仍活跃的预发送许可不会被其他请求抢收。发送与回收并发时共用许可行锁，只能出现“发送成功，未知调用继续占位”或“回收成功，旧发送被拒”之一。模型已有 beginModel 身份/代次约束、工具已有审计领取仍负责逻辑执行去重；容量结束不等于业务步骤完成。

## 恢复与兼容

GraphCapacityGateway 在只读 replay 之前恢复有事实支持的旧许可，因此原响应重放再次被业务校验拒绝也不会卡住容量。逻辑成功回执仅保留对 tracking_version=0 的兼容恢复，不能释放新协议中另一次未知物理调用。

图宿主启用时装配 GraphCallRecoveryWorker，每轮最多 100 条，默认间隔 1000ms，配置项 `workflow.agent.graph.call-recovery-interval-ms`。与完成收尾共享单线程 graphCompletionScheduler；单次仓储事务超时 5 秒，没有网络调用。扫描使用 SKIP LOCKED，并在取得锁后复核执行状态；持久停止即使租约未过期也不算活跃。数据库失败留待后续扫描，不伪造释放。扫描上下文限于受信 system worker，结束后恢复。

wire、v1/v2 binding、旧 Registry 重载保持兼容。自定义未接入发送/返回钩子的 Gateway 默认走旧保守协议，不能凭“没看到 dispatch”释放容量。同步调用栈上下文不继承到子线程，禁止嵌套容量作用域；异步适配器必须另行实现可信生命周期契约。

部署须先应用 V75，再启用新 Java 宿主；受限运行角色按既有流程取得新增表权限。不回填或重标历史许可，不删除旧隔离项。此轮只在隔离 PostgreSQL 执行迁移，未修改业务库或切换业务版本。

## 限制与验收

dispatch 提交后、真正调用之前的宕机，以及远端返回后、RETURNED 提交之前的宕机仍属未知；本地超时、adapter 抛异常、租约到期、Run 终态均不能证明远端已结束。此类许可继续隔离，需可信对账；不新增接受任意“证明字符串”的公开运维接口。嵌套模型/Embedding/Rerank、异步请求和远端 exactly-once 不在本次保证范围内。

新增真实 PostgreSQL 回归覆盖规划/纠偏重定向宕机、模型返回后 Plan 拒绝、工具返回后输出拒绝、迟到返回、未知调用不得凭逻辑回执释放、未发送回收屏障、预算不退款、停止孤儿与历史许可区分、发送/释放竞争、v1 撤权返回、请求/预算身份与事实 RLS。另测恢复 Worker 的批量上限、失败重试及可信上下文恢复。

全量入口：agent-runtime-langgraph 下 `uv run pytest -q`，结果 **80 passed（407.03 秒）**；内嵌 Java **194 tests，0 failures，0 errors，0 skipped**，其中 PostgreSQL 持久层 105 项、真实 Flowable 6 项。两个层级数字不相加。日志：`C:/Users/email/AppData/Local/Temp/pytest-of-email/pytest-17/test_java_tool_gateway_postgre0/java-gateway.log`。

全量后补充请求/预算身份绑定与事实 RLS 用例，定向 `GraphPersistenceIntegrationTest#physical*` **11 项通过**（10 项重跑、1 项新增），日志 `C:/Users/email/AppData/Local/Temp/graph-physical-final-3hf5gh4o/physical-final.log`。最终复核将模型请求构造及本地截止时间检查移至 dispatch 提交之前，避免尚未进入适配器的本地检查失败被归为已发送；调整后再次执行这 11 项，全部通过，日志 `C:/Users/email/AppData/Local/Temp/graph-physical-boundary-tnp_sepm/boundary.log`。不把两次定向重跑叠加为新增测试，也未在最后这个局部顺序调整后重复整套 Flowable 回归。

环境为 Java 25、uv Python 3.12、独立临时 PostgreSQL 18，使用 MAVEN_ARGS 指定本地离线依赖和 settings。没有调用真实模型；隔离集群由夹具退出时停止。Ruff 检查、18 个 Python 文件格式检查、`git diff --check` 通过。
