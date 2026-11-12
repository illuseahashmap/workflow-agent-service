# 开源 LangGraph 运行模块

选择 **LangGraph 开源库 + 官方 PostgreSQL checkpointer**，不依赖商业 Agent Server、LangSmith 账号或许可。锁定依赖见 `pyproject.toml` / `uv.lock`。

当前是 G0 的可执行契约切片，**不是已经替换 Java 执行器的服务**。MODEL/TOOL/VALIDATE 已通过 Python 图、HTTP、Java JDBC 授权/引用和 PostgreSQL 检查点联调，V65/V66 提供持久会话、整图命令和模型/校验回执。生产 Worker/HTTP、artifact 路由和容量调度尚未接通；现有 AgentVersion 和业务运行没有切换。

## 职责

- LangGraph：模型决策 → 多工具顺序调用 → 候选校验的图控制流、同步检查点、持久 interrupt/resume。
- Java 平台：工具桥接复用 Registry 的授权/Schema/幂等；MODEL 单次调用已有 Provider 端口，先存原响应再原子发布参数/回执；VALIDATE 共用原生拒绝/Schema/证据规则。`tests/fake_gateway.py` 是独立图测试替身，`tests/java_cognition_probe.py` 则连接真实 JDBC 权威/引用与 Registry，模型和版本/Provider 仓储仍使用确定性夹具。
- `RunBinding`：冻结租户、Run、版本、输入/主体引用、artifact、deadline 和逻辑调用上限。同一线程变更绑定明确拒绝，不偷偷新建线程。
- `InvocationRequest`：先持久化稳定操作 ID 和参数摘要，再调用网关；网关必须原子领取、比较请求哈希并复用回执。不能把每次 HTTP 重试当成新的逻辑操作。
- `CANDIDATE_READY` 不是业务成功；Java 还须复核原回执、当前权限和结果策略，事务性提交业务结果与完成 Outbox。

Python 不接收模型/MCP 凭据或任意服务 URL，不直读业务库；图内只存受限引用。引用不是授权，Gateway 必须检查来源与 Run 绑定。LangSmith SDK 是框架的传递依赖，但 `advance()` 显式关闭其自动云追踪，不需要开通服务。

## 检查点所有权

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

测试覆盖意图先提交、同租户双 Run、持久等待、版本/权限/预算、图互斥、进程强杀恢复、有界 HTTP 及 Registry 账本并发/丢 ACK。全套 52 项通过，其中 Java 启动测试内含 60 项 Java 回归：24 项 V65/V66 持久层/cognition 集成，包含真实 Python 图丢 model:2/validate:1 回复后的冷恢复、原响应先提交、串单、缺证拒绝与撤权。模型为确定性替身；不是 Flowable/P-17 联调，不将内嵌测试重复相加。

## 仍需完成的接入

1. 将独立意图路由、增量摘要、READ_RESULT 和有限 JSON 修复显式迁到图节点。当前 MODEL 不隐藏循环/修复/摘要调用；上下文超限明确拒绝。已有确定性证据校验不等于通用语义 Grounding，完整 P-17 等价仍须验证。
2. 基于已有整图命令仓储接通生产 Worker、查询/ACK 丢失对账、受保护 HTTP、artifact 发布路由及准入配额；业务 Attempt 与执行代次已分开，只调度图实例，不复制图内部 nextStep。实际 V65 已在隔离集群验证，历史全量升级及完整命令服务另验。
3. P-17 七场景和真实 Java/Flowable 故障回归，再做多实例公平配额、网络分区、取消及压力验收。
4. 通过 G0–G3 后只对新发布版本启用；旧运行排空后删除原生内部循环和恢复实现。

权威方案：[LangGraph 接入设计](../docs/architecture/langgraph-runtime-adoption-design.md)。
Wire 身份、超时和当前交付边界：[受治理 Gateway 契约](../docs/architecture/langgraph-governed-gateway-contract.md)。
