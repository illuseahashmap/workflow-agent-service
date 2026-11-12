# 开源 LangGraph 运行模块

选择 **LangGraph 开源库 + 官方 PostgreSQL checkpointer**，不依赖商业 Agent Server、LangSmith 账号或许可。锁定依赖见 `pyproject.toml` / `uv.lock`。

当前是 G0/G2 的可执行组件，**不是已经替换 Java 执行器的服务**。MODEL/TOOL/VALIDATE、V65/V66 持久层已有联调；V67 增加跨实例领取上限/等份轮转/调用隔离。认证 dispatch 客户端/Java 宿主、Worker CLI、artifact 领取、V68 全平台准入和账户配置/审计已接通。图宿主默认关闭，现有 AgentVersion 和业务运行没有切换。最新证据见[并发治理回归](../docs/quality/graph-capacity-regression.md)，环境变量和启动方式见[部署手册](../docs/operations/graph-dispatch-host.md)。

## 职责

- LangGraph：模型决策 → 多工具顺序调用 → 候选校验的图控制流、同步检查点、持久 interrupt/resume。
- Java 平台：工具桥接复用 Registry 的授权/Schema/幂等；MODEL 单次调用已有 Provider 端口，先存原响应再原子发布参数/回执；VALIDATE 共用原生拒绝/Schema/证据规则。`tests/fake_gateway.py` 是独立图测试替身，`tests/java_cognition_probe.py` 则连接真实 JDBC 权威/引用与 Registry，模型和版本/Provider 仓储仍使用确定性夹具。
- `RunBinding`：冻结租户、Run、版本、输入/主体引用、artifact、deadline 和逻辑调用上限。同一线程变更绑定明确拒绝，不偷偷新建线程。
- `InvocationRequest`：先持久化稳定操作 ID 和参数摘要，再调用网关；网关必须原子领取、比较请求哈希并复用回执。不能把每次 HTTP 重试当成新的逻辑操作。
- `CANDIDATE_READY` 不是业务成功；Java 还须复核原回执、当前权限和结果策略，事务性提交业务结果与完成 Outbox。

Python 不接收模型/MCP 凭据或任意服务 URL，不直读业务库；图内只存受限引用。引用不是授权，Gateway 必须检查来源与 Run 绑定。LangSmith SDK 是框架的传递依赖，但 `advance()` 显式关闭其自动云追踪，不需要开通服务。

## 检查点所有权

`SliceBudget` 默认每片最多 16 次 Gateway 调用、30 秒协作时间阈值。在调用边界保存待执行意图并进入 SLICE interrupt，恢复不会递增已有操作 ID。时间阈值不抢占已发 HTTP；单次调用还有独立硬超时。Worker 的同步 RPC 占槽位/连接，调用返回或挂起后才释放，不宣称已完成异步 I/O。`poll()` 必须由宿主持续驱动，RPC 有界、续租频率与租约共同配置；服务停止时阻止后续动作，但不伪造远端取消。

生产持久化入口是 `exclusive_postgres_checkpoint(dsn, binding.thread_id)`：在**同一个、不自动重连的数据库会话**上取得 advisory lock，并执行全部官方 saver 读写。竞争执行返回 `RuntimeBusy`，不得 TTL 强抢或另起随机 thread。连接失效后旧 saver 不能恢复写入，外部调用仍另受 Java 执行代次约束。

这里没有跨网络 exactly-once 保证。外部成功但回执未保存的未知窗口仍由平台对账/人工处理；数据库锁释放时间取决于连接故障检测。一个活跃调用占一个数据库连接（非长事务），生产 Worker 必须有界，并为命令、Gateway、检查点连接分别预算。`advance()` 当前是同步图执行，不代表已经完成异步容量优化或步骤级分布式调度。

`initialize_checkpoint_store(migration_dsn)` 仅在部署迁移阶段调用；运行账号不应拥有 DDL 权限。官方 saver 的 schema/迁移独立于 Java Flyway，不能在业务数据库随意 setup。图实例不可跨不同 `thread_id` 的所有权上下文共享。

## 本地回归

在仓库根目录执行（需安装 uv；没有 Docker 也可以）：

```powershell
./scripts/test-langgraph-runtime.ps1 -PostgresBin 'C:/Program Files/PostgreSQL/18/bin'
# 同时构建 Java 并跑真实 HTTP / PostgreSQL 工具账本联调（需要 JDK 25）
./scripts/test-langgraph-runtime.ps1 -PostgresBin 'C:/Program Files/PostgreSQL/18/bin' -IncludeJava
```

脚本创建临时独立 PostgreSQL 集群并静默启停，不读取业务库连接或业务凭据。不指定 PostgreSQL 时只运行本地语义测试，不算数据库验收。

Linux/CI 可提供一次性数据库 `WORKFLOW_LANGGRAPH_TEST_DATABASE_URL`，**不能指向业务库**：

```text
cd agent-runtime-langgraph
uv sync --frozen
uv run ruff check .
uv run ruff format --check .
uv run pytest -q
```

测试覆盖意图先提交、同租户双 Run、持久等待、连续预算让出、Worker 槽位/交接/失租、版本/权限/图互斥、进程强杀、有界 HTTP、认证派发协议、全平台准入、共享账户与 Registry 账本并发/丢 ACK。当前数量及独立 Spring 安全链回归见[并发治理记录](../docs/quality/graph-capacity-regression.md)。模型为确定性替身；不是 Flowable/P-17 联调，不将内嵌测试重复相加。

## 仍需完成的接入

1. 将独立意图路由、增量摘要、READ_RESULT 和有限 JSON 修复显式迁到图节点。当前 MODEL 不隐藏循环/修复/摘要调用；上下文超限明确拒绝。已有确定性证据校验不等于通用语义 Grounding，完整 P-17 等价仍须验证。
2. 接通命令/未知外呼可信对账、最终业务结果接纳与 artifact 发布路由。认证宿主和准入已实现但默认未启用；业务 Attempt 与执行代次分开，只调度图实例，不复制图内部 nextStep。V65–V68 在隔离集群验证，历史全量升级和最终业务提交另验。
3. P-17 七场景和真实 Java/Flowable 故障回归，再做多实例公平配额、网络分区、取消及压力验收。
4. 通过 G0–G3 后只对新发布版本启用；旧运行排空后删除原生内部循环和恢复实现。

权威方案：[LangGraph 接入设计](../docs/architecture/langgraph-runtime-adoption-design.md)。
Wire 身份、超时和当前交付边界：[受治理 Gateway 契约](../docs/architecture/langgraph-governed-gateway-contract.md)。
