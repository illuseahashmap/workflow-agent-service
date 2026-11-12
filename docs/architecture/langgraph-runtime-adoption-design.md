# LangGraph 内部运行时接入与平台治理设计

状态：开源部署路线已选定；G0 图模块与 Java 工具桥接已有联合回归，生产身份/派发及 G0–G3 完整门禁待完成

更新时间：2026-11-11

关联：[长期设计](long-term-design.md)、[资源治理](agent-resource-governance.md)、[协作模型](agent-collaboration-design.md)、[计划](../status.md)、[D-1 / P1-3 / P1-6](../quality/known-issues.md)

## 1. 决策与官方能力对照

采用 LangGraph 承担 Agent 内部循环、分支、并行、状态、Checkpoint 和中断恢复；Java 保留业务 Run、权限、配额、工具回执、证据验收与 Flowable 完成权。**撤回自研通用 ExecutionTask/Continuation 调度器方向，不同时维护两套内部游标。** 这不是“LangGraph 自动满足高并发”，也不是今天已经迁移。

2026-11-11 核对官方资料，区分已有框架能力与本项目接入要求：

| 资料 | 已有能力/限制 | 项目决策 |
| --- | --- | --- |
| [Persistence](https://docs.langchain.com/oss/python/langgraph/persistence)、[Checkpointers](https://docs.langchain.com/oss/python/langgraph/checkpointers) | 图状态、super-step 检查点、pending writes；历史 replay 会重新执行后续节点 | 复用内部恢复，不再自研图任务依赖；replay 不是业务安全重试 |
| [Interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts) | resume 从中断节点开头重新执行；中断前副作用须幂等 | 外呼前持久化稳定意图，工具回执仍由平台管理 |
| [Agent Server](https://docs.langchain.com/langsmith/agent-server) | 是图库之外的服务交付层；生产自托管有商业许可要求 | 不采用商业服务；不能把其队列/Worker 能力记为 OSS 图库已有能力 |
| [LangChain4j Agents](https://docs.langchain4j.dev/tutorials/agents/) | Agentic 仍标实验性，默认异步待执行任务随 JVM 退出丢失 | 不与 LangGraph 编排同一链；继续复用 Java 模型、Embedding、MCP 适配 |

选型原因是内部编排持续演进与现有执行器重叠，不是工具数量或框架流行度。容量治理、授权、副作用和业务完成均不因选型自动解决。

## 2. 三层职责与唯一权威

```text
Flowable：业务网关、人工审批、流程生命周期
    ↕ Java 应用服务及完成 Outbox/Inbox
Java 平台：Run / AgentVersion / 权限 / 配额 / 结果验收
    ↕ 持久提交、查询、取消、恢复命令及结果核对
开源 Python 运行模块：LangGraph 内部图状态、Checkpoint、中断
    ↕ 受信执行身份 + 稳定 operationId
Java Model / Tool / Knowledge Gateway：适配、授权、回执、证据
```

| 事实 | 唯一权威 | 另一侧保存什么 |
| --- | --- | --- |
| Run 业务状态、deadline、主体、最终结果 | Java | 图保存关联 ID，不直接完成 Flowable |
| 图当前位置、节点依赖、合并状态、重试位置 | LangGraph | Java 保存 backend/thread/run/checkpoint 引用与审计，不推算 nextStep |
| 工具请求身份、参数哈希、执行状态、可信回执 | Java 工具账本 | 图持引用与允许使用的内容 |
| 模型预算、已持久化原响应、费用事实 | Java Model Gateway | 图按响应引用推进；Gateway 不决定下一图节点 |
| 知识快照、授权、原文、Citation、Grounding | Java | 图消费受限证据，不能指定任意租户或绕过授权读库 |
| BPMN 人工任务与流程去向 | Flowable / Java | 图只接收验证过的人工输入或完成结果 |

Java Step 在目标后端中是幂等审计投影，不再用于恢复新图的执行位置。当前尚未切换，原生 Checkpoint v5 继续服务现有路径；切换后只服务旧运行排空。新后端不额外维护可驱动执行的 v5 快照。图状态只能通过运行模块接口变更，Java 不直写其检查点表。

## 3. 部署与组件复用

用户于 2026-11-11 明确选择开源路线：采用 **Python LangGraph 开源库 + 官方 PostgreSQL checkpointer**，不购买/依赖 Agent Server 或 LangSmith 服务。`agent-runtime-langgraph` 锁定 `langgraph==1.2.20`、`langgraph-checkpoint-postgres==3.2.0`、`psycopg==3.3.8`，完整传递依赖由 `uv.lock` 固定。软件许可与部署资源费用是不同问题，不承诺数据库、模型或基础设施免费。

不复制商业 Agent Server。平台仅提供已需承担的持久业务命令、准入与公平资格；Python 有界 Worker 领取图实例执行资格，使用框架原生状态推进。不得另建通用节点任务依赖、nextStep、内部重试游标或图画布。当前模块有同步 `advance()`、官方 saver、HTTP 适配和 Java 工具桥接，生产身份/派发及 MODEL/VALIDATE 尚未接通；图库不等于分布式服务，完整 G0/G2 仍须实测。

PostgreSQL saver 与会话级 advisory lock 使用**同一个不自动重连的连接**；图运行结束/持久挂起后关闭连接释放所有权。不以 TTL 过期强抢存活会话，数据库连接丢失后旧 saver 不得自行重连写入。Java 的 session generation 另外阻止旧外呼/提交。每个活跃图调用占一个连接但不持长数据库事务；Worker、连接和在途须统一有界，故障接管时延还受 TCP/数据库失联检测影响，不能当成已实现低延迟接管。

图库的 LangSmith SDK 传递依赖不代表购买服务。模块显式关闭自动云追踪，未来可观测出口由平台统一配置。数据库 setup 只在部署迁移阶段执行，运行账号不使用 DDL 权限；官方 saver schema 与 Java Flyway 分开管理。

Java 保留 LangChain4j Provider、Embedding、MCP Client；图经受治理端口访问，Python 不保存 Provider/MCP 密钥、不直连业务库。不再引入 LangChain4j Agentic 或自动执行工具的高层代理。框架决定下一动作，平台决定动作是否获准。

## 4. 身份、版本与跨服务命令

- AgentVersion 冻结 executionBackend、graphArtifactDigest、graphStateSchemaVersion、运行服务/持久化兼容版本，以及工具快照、知识 Profile、预算和验证策略。部署路由须保留旧 artifact，不能以同名图最新代码恢复旧状态。
- platformRunId 是业务目标；threadId 是隔离的图状态空间；backendRunId 是一次平台登记的 runtime 调用（不是商业服务 ID）；operationId 是逻辑调用；commandId/eventId 用于去重。身份不互相替代，不接受模型自报租户/主体。
- 一个业务 Run 固定一个后端与状态线程。普通暂停/resume 可产生新 backendRunId，但不增加业务失败 Attempt。业务 Attempt 只计经批准的失败尝试，图节点调用重试另记，指标不得混用。
- 新 `AgentExecutionBackendPort` 提供异步 submit/query/resume/cancel，提交返回关联引用，不占 Java Worker 等完整 Run；DTO 不暴露框架类型。请求携带协议版本、commandId、Run/主体、artifact、输入引用/摘要、绝对 deadline 和预算版本。
- resume 指明 waitRequestId、最新 checkpointId 与当前 session generation，不能只凭 threadId 恢复任意历史状态。平台命令登记负责薄适配去重，不新增内部节点状态机。G0 必须证明 ACK 丢失后仍能找回同一次提交，不能重建随机线程。

Java 同一事务完成准入、Run 和待发送命令记录，提交后唤醒、扫描兜底；不持数据库事务调用远程服务。ACK 丢失按稳定关联查询对账，未证明未接收前不重复启动独立执行。内部结果和下一节点由框架持久化机制接管，**不要求图 Checkpoint 与 Java Run 跨库原子提交**，不为每个图节点复制 Java Outbox。

图完成只产生结构化候选和证据/回执引用。Java 核验租户、Run、版本、权限、已登记操作、Schema、拒绝信号与 Grounding，再在平台事务写最终结果和 Flowable 完成 Outbox。消费方继续 Inbox 去重并验证活动节点，图成功不等于审批通过。

回调丢失由 Java 查询后端持久输出补偿；事件至少一次，按后端运行/序号去重，SSE 仅展示。Java 校验通过但终态事务失败，重验同一候选与当前权限，不要求模型重答。

业务审批仍由 Flowable 管理。图需要人工输入时提供稳定 waitRequestId，平台幂等关联人工任务；验证任务状态、修订号、权限后 resume。图挂起与 Java 登记不是同事务，须通过可查询的持久等待事实、幂等登记与对账覆盖先后竞态。

## 5. 回放、幂等、重试与摘要

1. 独立“确定意图”节点持久化 operationId、调用标识、参数摘要与预算引用，可靠提交后才能进入外呼节点。验证所选部署的同步持久化边界，不能依赖异步 checkpoint 恰好先落库。
2. 工具键保持 `platformRunId + logicalOperationId + toolCode`；参数哈希单独比较。恢复、Worker、backendRun、Attempt 改变不换键；FAILED/过期重新领取仍查哈希。
3. 工具成功但图 checkpoint 未写，恢复已提交意图并授权回用原回执，不重新选择工具。模型原响应已持久化也按稳定意图回用，解析/验证不依赖再生成。
4. 远端成功但本地未保存回执仍存在未知窗口。Checkpoint 不保证外部 exactly-once；有外部幂等/查询能力才可对账，未知写操作人工核验。模型响应丢失可按显式预算重复生成并记录成本，不能承诺零重复费用。
5. 图负责节点重试位置/节奏，Java 强制次数、Token、费用、deadline 总上限。SDK 隐式重试关闭或纳入同一预算，不叠加“平台三次 × 图三次 × SDK 三次”。普通等待、resume 和安全接管不算业务失败重试。
6. 历史 replay/fork 不是默认运维重试；获准的业务重新执行另建 Run 并关联来源。不能把参数变化、换制度快照混入同一逻辑操作。

摘要和 JSON 修复也经过 Model Gateway、证据绑定与预算。增量摘要、按需原文回读、防串单仍是平台业务约束，迁移为图节点不能退化为默认消息裁剪，不能让摘要取代关键原文。

## 6. 容量、公平及长等待

Java 负责业务准入、图启动/恢复命令、模型/工具调用许可；Python 有界 Worker 消费运行资格，LangGraph 负责图内推进和恢复。命令队列不是图节点任务队列，OSS 图库不提供平台公平分配。详细配额权威与指标见[资源治理](agent-resource-governance.md)。

- 平台/租户 outstanding 有界且包含暂停 Run；全局/租户/供应商账户模型/连接器在途分别限制。幂等重复不再占位；超额明确拒绝。Flowable 内准入失败回滚本次推进，不能留下无 Run 的等待节点。
- 按租户公平分配业务启动和受限调用；共享凭据下多个 Provider ID 归入同一额度域。现有 MCP Redis 治理在统一切换前保留，不双扣同一许可。
- 无调用资格返回类型化等待与稳定 waitRequestId，图在恢复边界持久挂起。Java 只排“线程何时有资格恢复”，不存 nextStep；等待登记/图挂起竞态靠事实核对，丢唤醒靠扫描。
- 每次等待节点调用只触发一个 interrupt；恢复后资格失效则持久化新的等待状态，经图边再次进入等待节点，不在同一节点累积 `while + interrupt` 的历史恢复序列。等待前登记和恢复命令均幂等。
- 必须验证挂起**确实释放执行槽**；async、虚拟线程或 interrupt 字样不是证明。恢复资格过期允许再等待，但无忙循环；上游图队列也有界，无资格线程不能挤满 runtime。
- Python 异步 I/O、受控 Java 虚拟线程降低阻塞成本，连接、CPU、响应体和 checkpoint 均有上限。外呼在途仍占许可；远端状态未知进入隔离额度，不凭本地 TTL 视为远端结束。

## 7. 接管、撤权和安全

图线程互斥和迟到检查点防护通过官方 saver 同会话所有权外围保护验证，不声称开源库自带分布式租约。Java 不能因轮询超时另启竞争执行，先查询/取消/核对原执行，再按后端接管契约恢复。存活的数据库所有者未退出时，后继者收到忙状态而非强抢；数据库失联网络分区与有界取消仍列入 G2。

平台 executionSessionGeneration 拒绝过代调用授权与候选提交，**不替代图存储 fencing**。若旧 Worker 能污染最新图检查点，接入门禁失败、禁止自动接管；不能增加 Java nextStep 掩盖。存储写入条件、网络分区、旧 Worker 复活都要实测。

图持久化空间与 Java RLS 是不同边界。用户只访问 Java，不可自选 threadId 或访问管理接口。服务间用短期、受众受限的执行身份；模型/工具调用、回执复用、原文读取、最终验收均重验当前权限，冻结版本不是永久授权。

撤权后旧摘要可能含敏感内容，不能只过滤新调用就发送整个旧上下文。须重建可证明授权的上下文，否则终止或人工接管；网关不能信任图自报“引用已验证”，须核对平台登记的来源/操作与原回执。取消/deadline 后阻断新调用与成功完成；迟到结果只审计/结算，不改终态。服务存储的密钥、备份、租户映射、加密及保留删除策略纳入验收。

## 8. 实施与替换门禁

保留业务 Run、版本、工具/知识治理、回执、证据/摘要规则、结果验收、Flowable Outbox/Inbox。迁移后删除原生内部循环、游标/Checkpoint 编码及同一图的 Java 领取/Recovery；本轮自研 ExecutionTask/Continuation 不实施。

**G0 — 接入契约及部署实证：** 锁定 OSS 图库/持久化版本；验证平台命令桥的稳定提交/查询、重启、持久等待/取消、线程互斥与迟到 checkpoint、artifact 路由和隔离。输出“框架原生/平台外围/未满足”矩阵。G0 未过不默认部署可用。

2026-11-11 决策更新：官方[自托管说明](https://docs.langchain.com/langsmith/deploy-to-self-hosted-overview)的 Enterprise 许可适用于其服务产品，不适用于我们选定的 OSS 图库模块。用户确认开源后，商业授权不再是阻碍；本地测试使用独立临时 PostgreSQL 18 集群，无需 Docker 或模型密钥。已完成的代码与证据见第 10 节，G0 整体仍未通过。

**G1 — P-17 等价基线：** 图实现路由 → 取证/工具 → 校验/缺证处理，调用现有治理网关。沿用本机七场景评分同 Run 引用、回执、Flowable 去向；当前按用户要求不测方舟真实模型。追加两笔同租户采购、连续两次压缩、摘要预算调小恢复、JSON 修复、撤权和串单，保持 Top5、20,000.01 元及“必要时”规则。

**G2 — 双实例故障及容量：** 注入提交 ACK 丢失、意图提交前后、工具成功/图 checkpoint 未写、图答案已存/Java Run 未提交、resume 先到/登记后到、重复事件、旧 Worker 复活。成功工具/持久化候选恢复不依赖模型重答。验证硬配额、慢供应商隔离、公平、有界等待、取消及未知额度隔离。

工程容量目标（不是已测成绩或生产 SLA）：两执行实例、20 活跃租户、平台在途 64、每租户 8、outstanding 总 1,000/每租户 200；突发 2,000 次提交应有明确拒绝。慢 Provider 30 秒/并发 8，独立快 Provider 1 秒；依赖额度可用的普通租户等待 P95 ≤ 5 秒，30 分钟线程/连接/等待项不持续增长。报告硬件、版本、负载、实际在途、完成吞吐和失败数，不能用入队速度冒充容量。

**G3 — 权威切换及删旧：** 仅新发布版本启用新 backend，旧 v5 Run 按原路径排空。旧 artifact/备份保留到恢复保留期结束；无活动/待恢复旧 Run 后移除旧调度及游标逻辑，记录净删除量。新 Flyway 增量添加，禁止修改已发布脚本。

## 9. 升级、回滚和完成判定

Java 业务库与运行服务库分别管理 schema、权限、备份和保留期；跨库恢复核对命令、回执及完成事件。图升级不覆盖旧 artifact；状态迁移须证明恢复等价，否则旧服务排空。

回滚是停止新后端准入，让已有图继续使用匹配 artifact 或转运维；不能把 LangGraph checkpoint 强转 v5，不能让 Java/图同时接管。新业务版本可选已验收后端，不修改已发布版本绑定。

完成须具备 G0–G3 证据、P-17/故障回归、容量数据、运维操作和旧代码退出。现有 P-17 本地 7/7 是原生执行器基线，不是 LangGraph 或真实模型成绩。下一步补 G0 命令/Gateway 接入并进入 G1，不继续自研通用内部调度，也不因选型关闭 P0/P1 发布门禁。

## 10. 已实现切片与回归证据（2026-11-11）

代码入口：[开源运行模块](../../agent-runtime-langgraph/README.md)。依赖固定并新增独立 CI；本机 `scripts/test-langgraph-runtime.ps1 -PostgresBin 'C:/Program Files/PostgreSQL/18/bin'` 使用独立临时集群，未触碰业务库。

| 能力 | 归属/实现 | 当前证据与限制 |
| --- | --- | --- |
| 图内控制流与恢复 | LangGraph StateGraph / sync durability / interrupt | MODEL → 多 TOOL → VALIDATE；不自研内部游标 |
| 意图与回执引用 | 平台契约 + 图节点 | 先提交意图再外呼；参数哈希独立，Gateway 测试替身持久保存回执 |
| 挂起/恢复 | 框架 interrupt + 当前 checkpoint/wait 身份检查 | 连续两次等待、过早/过期恢复拒绝；等待返回后数据库锁释放 |
| 同图互斥、禁止旧检查点写入 | 同连接 advisory lock + 官方 PostgresSaver | 独立连接竞争、数据库终止旧连接后旧 saver 写入失败 |
| 进程级冷恢复 | 官方 PostgreSQL saver + 测试回执账本 | 工具成功、候选保存、验证成功窗口强制退出；新进程复用，无新模型响应 |
| 当前授权/跨 Run/版本约束 | 受治理 Gateway 契约 + RunBinding | 同租户两 Run 隔离、拒绝串单回执、变更绑定拒绝、前后重验代次/权限 |
| 业务命令/Flowable/公平配额 | Java 治理与后续适配 | **未接通**；没有发布 LANGGRAPH AgentVersion，没有切换原生路径 |

本地结果：**27 passed（21 图逻辑 + 6 PostgreSQL），0 skipped**；Ruff 检查及格式检查通过。Python 3.14 下传递依赖 LangSmith SDK 有 `asyncio.iscoroutinefunction` 弃用提示，不影响测试；未通过压制警告掩盖。数据库测试中先修复 Windows 后台进程继承 PIPE 导致测试启动等待的问题，再完成全量回归。

生产 Gateway、受信认证、原子回执、HTTP/持久命令桥、取消、跨 artifact 路由、摘要/JSON 修复/证据门禁迁移、G1 P-17 与 G2 网络分区/容量均未实现完整联调。当前候选复用测试不等于 Java Run 事务回滚/Flowable 去向验收，不能用测试替身结果关闭 P1-3/P1-6。

## 11. Java 工具桥接继续交付（2026-11-11）

新增 [受治理 Gateway 契约](langgraph-governed-gateway-contract.md)。Java 工具操作经原 Registry 和 JDBC 原子账本执行，不另建工具幂等机制；Python 传引用而非原始参数，双方核对 Binding/operation/request 摘要。能力仅放请求头，跨 Run/过代/撤权拒绝；返回错误只含稳定码，临时工具失败保留可重试类别。HTTP 入口没有自动注册，MODEL/VALIDATE 未实现时明确拒绝。

工具桥接切片当时的联合结果 **52 passed / 0 skipped**：21 图逻辑、24 HTTP、6 PostgreSQL 图测试、1 Java 启动测试。后者当时内含 36 项 Java 回归（含 4 项架构约束），其中真实 Python HTTP → Java Registry → PostgreSQL 工具账本验证丢 ACK 重放、三 Run 独立能力与引用、6 请求并发仅执行一次。HTTP 测试的执行能力和引用仓储为限定夹具，不是生产签发/派发链。接口错误映射已移回应用层，避免越层依赖领域类型；源码及测试通过项目 Checkstyle，Python 通过 Ruff。

## 12. 整图命令与执行身份持久层（2026-11-11）

新增 V65，不修改旧迁移；默认既有 Run 为 NATIVE，原生领取排除 LANGGRAPH。`JdbcGraphExecutionStore` 实现持久能力授权与整图派发端口，`JdbcGraphReferenceStore` 实现不可变参数和现有工具账本引用。它们没有自动注册生产 Bean/HTTP/Worker，不迁移业务运行，也不签发公开服务令牌。

- 可信入口 reserve：锁 Run，读取已发布版本/输入/提交人/节点工具子集，冻结 artifact/deadline/预算，原子登记整图命令与后端。拒绝已开始或有历史 Attempt 的运行；插入命令失败时全部回滚。
- 单条 claim：先取得准入容量再调用。短租约、随机能力哈希、数据库时间、generation 防迟到。ACK 丢失/领取者退出后重领保留 command ID，轮换能力及代次；正常等待则原子保存 opaque checkpoint/wait、新 command ID 和 available_at，释放能力，不增加 Attempt。
- 图内位置仍只有 LangGraph Checkpoint 一份权威；Java 不读取或决定节点游标。命令 ACK 只保存候选/拒绝观察，不能标记业务成功或推进 Flowable。
- 引用绑定 tenant/Run/binding/操作/工具/摘要，数据库不可变；工具回执引用核验已有成功账本。Run 行锁和提交前授权复核防止过代、撤销与写入期间租约过期；统一锁顺序且事务内不调用远端。
- 本轮交叉审查修复的回执稳定键序、参数引用核验、过期写入回滚、续租/交接时钟条件，及验收明细见 [Gateway 契约](langgraph-governed-gateway-contract.md#持久交接与交叉审查)。

新增 13 项真实 PostgreSQL 集成，实际执行 V65、非 owner RLS 读写检查；联合测试仍为 52 项，内嵌 Java 从 36 增至 49 项，均无失败/跳过。前置表是最小测试夹具，不替代全历史 Flyway 升级；跨语言 HTTP 测试与持久层测试尚未组合为生产完整链。

**V65 切片交付时仍未完成**：生产派发循环/查询对账、artifact 发布路由、准入配额与受保护 HTTP，MODEL/VALIDATE 及模型结果授权引用提交，摘要/JSON 修复/证据门禁、P-17/Flowable 等价和容量实测。MODEL/VALIDATE 后续进展见第 13 节。保留 G0–G3 门禁，不把这些表和端口称为已经可运营的调度服务。

## 13. 模型原响应与确定性校验接入（V66，2026-11-11）

`GraphCognitionGateway` 已复用既有 Provider 端口完成单次模型调用；`JdbcGraphCognitionStore` 持久化原响应、候选/工具决策和校验回执。它不运行 Agent 循环，图决定下一次 MODEL/TOOL/VALIDATE；原生执行器没有被套壳调用。

- 模型操作按逻辑编号及完整输入引用历史核验，物理调用次数单独累计。相同代次的并发请求返回执行中；原响应已保存则恢复解释而不重答。原响应尚未保存的外部不确定窗口仍可能重复计费，不能宣称外部模型 exactly-once。
- 原响应先提交，参数引用和模型回执随后原子发布。证据、候选和模型回执不能串 Run；已产生候选后不能再新建模型操作改答。原始模型响应、已完成回执和校验结论不可重写。
- `AgentCandidateValidator` 与原生执行器共用拒绝、Schema、禁止把工具命令当答案的门禁；`TaskMemory.Context` 解耦执行框架，复用现有主体/决策/CitationClaim 证据校验。校验失败不会调用模型修复，也不会推进 Flowable。
- 真实 Python 图 → 有界 HTTP → JDBC 执行身份/引用/模型回执 → Registry 链路已验证。模型替身固定返回工具调用再答案，依次丢掉候选和校验回复，再打开 PostgreSQL 图恢复，模型 2 次、工具 1 次、校验回执 1 条，Java Run 仍未成功提交。
- 实际执行 V65/V66 的持久集成增至 24 项；外层联合 52 项、内嵌 Java 60 项通过，无失败/跳过。原生 Executor/Worker/结果策略/Registry/Gateway 另复测 66 项通过，不重复累加。

交叉审查补齐了重复 JSON 字段隐藏拒绝、原响应写/读限制不对称、错误操作提交为校验的防线；网关业务小数按 BigDecimal 解析，避免接近金额边界的值先被 double 四舍五入，精度回归覆盖 20,000.000000000001。详细故障与权限回归见 [Gateway 契约](langgraph-governed-gateway-contract.md#model-与-validate-的持久边界v66)。

**下一步仍按 G0/G1 顺序**：迁移独立意图路由、增量压缩、READ_RESULT 和有限 JSON 修复为图节点（不得塞回 Java 隐藏循环）；接生产准入/派发 Worker、HTTP、artifact 路由与自动对账；再验收 P-17、双采购压缩/恢复和 Flowable 提交。当前超长原文上下文明确拒绝，尚不具备原生完整能力等价，也没有切换业务版本。

下一步：落地生产能力/引用权威与持久派发，再接 MODEL/VALIDATE、迁移摘要/修复/Grounding 并跑 P-17。取消、网络分区、受控异步/连接复用、公平性与容量仍待验收；客户端 OS DNS 解析硬截止也列入传输部署门禁。不得因局部联调通过便开放新 AgentVersion 或关闭 P1-3/P1-6。
