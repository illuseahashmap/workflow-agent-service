# LangGraph 结果到 AgentRun / Flowable 的可靠交接

日期：2026-11-12。范围：V69、首次图执行的业务 Attempt、最终结果验收和既有完成 Outbox/Inbox。使用隔离 PostgreSQL、确定性模型响应和真实 Flowable；不调用方舟、不修改业务数据库，不代表生产容量或全部 G1/G3 通过。

## 权威与事务边界

1. 第一次领取图实例时建立一个业务 Attempt，并记录 `QUEUED → RUNNING`。后续 dispatch generation、挂起、接管都复用它；图内位置仍仅由 LangGraph Checkpoint 决定。
2. `acknowledge` 持久化不可变观察并释放执行能力，不直接成功、不推进流程。ACK 丢失可以安全重查；不会因为重发而产生第二个完成事件。
3. 独立 `GraphCompletionWorker` 有界轮询 ACK。`JdbcGraphCompletionStore` 锁 Run，复核冻结的版本、主体、输入、流程实例/执行/节点/激活标识、映射、失败策略、deadline 和业务 Attempt。不能仅凭图返回的 `CANDIDATE_READY` 放行。
4. 验收从数据库重建候选、模型回执、工具原参数/成功回执及验证记录；核对租户、Run、引用顺序、摘要、验证决定。`GraphCandidateGate` 与图 VALIDATE 共用拒绝、Schema、主体/决策/引用门禁；最终提交前再次检查当前工具/知识回读权限。没有模型重答或隐藏 JSON 修复。
5. 同一事务提交 Run/Attempt 终态、状态历史、输出快照、完成 Outbox、不可变验收标记；V68 准入额度随终态事务一起释放。失败回滚这些变更，候选与原回执不丢失。
6. 既有完成消费者负责 Outbox/Inbox、节点激活匹配和 Flowable 推进；接收任务触发和 Inbox/Run 标记在同一事务提交。图服务没有业务流程推进权限。

## 故障矩阵

| 注入点/风险 | 预期及回归 |
| --- | --- |
| 候选和验证回执已保存、业务 Run 未提交 | 重建 Java store 后直接验收原结果，不再调用模型或工具 |
| 验收事务插入 Outbox 后抛异常 | Run、Attempt、历史、Outbox、准入释放与完成标记全部回滚；ACK 留存，退避后重试 |
| 插入 Outbox 后强制终止 PostgreSQL 事务会话 | 数据库回滚未提交终态/事件；新连接可从 ACK 和原候选恢复，不重答 |
| 提交成功、调用方未收到成功 | 重启再次扫描不重复发 Outbox；重复 ACK 不能改写原观察 |
| 两个验收 Worker 竞争 | Run 行锁及提交后重查保证只有一个业务完成事件 |
| 同租户另一 Run 的候选/验证引用 | 标记 `BLOCKED / GRAPH_RECEIPT_INVALID`，不能串单成功 |
| 验收前撤销工具授权 | 按类型化拒绝提交失败，不发布答案、不重答 |
| 原验证已拒绝 | 不能在验收阶段变成成功；使用已有人工复核/保留现场策略 |
| Run 已取消或已终态 | `DISCARDED`，不覆盖状态，不产生新的完成事件 |
| 流程激活或绑定被修改 | `BLOCKED / GRAPH_BUSINESS_BINDING_MISMATCH`，不将旧答案写给新节点 |
| 本地验收期间越过 deadline | 提交 `TIMED_OUT`，不保存迟到答案 |
| Flowable 触发后、Inbox 完成前故障 | Flowable、Inbox 和 Run 处理标记一起回滚；重投同一事件只创建一个后续任务 |
| Flowable 已换为同节点的新激活 | 旧完成事件不能触发新激活 |

测试入口：

```powershell
./scripts/test-langgraph-runtime.ps1 -PostgresBin 'C:/Program Files/PostgreSQL/18/bin' -IncludeJava
```

联合脚本强制核验 Java PostgreSQL 与 Flowable 报告非跳过。`GraphPersistenceIntegrationTest` 使用最小前置表并执行真实 V65–V69；`GraphCompletionFlowableIntegrationTest` 使用独立空数据库，执行全量 Flyway 与真实 Flowable。Java 验收覆盖事务异常和 PostgreSQL 会话强杀，不冒充 Java 宿主操作系统强杀；既有 Python saver/进程恢复测试另行保留，不混称生产网络分区验收。

最终实测：

- 联合脚本 **63 passed，0 skipped**（包含一个 Java 启动测试，不与下项简单相加）。
- 内层 Java **105 项通过**：GraphPersistence 55、GraphToolGateway 19、GraphGatewayIntegration 1、Registry 12、架构 4、真实 Flowable 4、HostConfiguration 3、HostController 7；均未跳过。相对此前新增 12 项持久交接测试和 4 项 Flowable 集成测试。
- 独立状态机/原生 Worker/完成协调与恢复回归 **38 项通过**，其中 Host 的 10 项与联合脚本重叠；状态机新增 2 项委托 Attempt 测试。合计新增 18 项测试，不把重复运行计为新增。
- Ruff 检查/格式、改动 Java 范围 Checkstyle、`git diff --check` 通过。Python 依赖有现存 `asyncio.iscoroutinefunction` 弃用警告，未导致失败。

## 交叉审查修正

- Flowable 完成协议依赖业务 Attempt：首次图领取建立一次，后续 generation 不递增重试数，不削弱消费者的匹配规则。
- 拒绝使用“当前 Run 的字段”代替冻结身份：V69 冻结完整业务绑定；旧预约无法补证，不自动追认。
- 拒绝让图验证成功绕过最终权限：只复用不可变原件，重新执行本地确定性门禁；不重新调用模型。
- 防止验收失败占据队首：基础设施失败回滚后单独记录有界退避（1–60 秒），其他 Run 可继续；完成记录不会被失败处理覆写。
- deadline 采用数据库时钟，在验证结束后判断；冻结身份中的 deadline 使用 epoch，避免连接时区差异造成错误拒绝。
- ACK 和最终验收记录受数据库不可变约束；缺证/身份冲突转明确阻塞状态，不进行无限热重试。

最终按迁移兼容、领域状态机、事务/锁顺序、引用归属、权限与 Flowable 消费边界复查，未发现本次交接范围内新的阻断项。以下未完成项仍作为后续门禁，不以本轮回归关闭。

## 未关闭的边界

图宿主仍默认关闭，AgentVersion 未自动切换。未知外呼可信对账、图运行整体取消/超时/暂停运维闭环、业务版本 backend/artifact 发布门禁、路由/压缩/回读/修复节点等价、持续多实例负载与 G1/G3 仍需完成。当前验收器处理已 ACK 的结果；不冒充所有未 ACK 失联 Run 的超时恢复器。最终授权采用本地授权检查，不承诺跨远端授权系统的全局事务。
