# 图派发宿主、Run 准入与账户配置

更新时间：2026-11-12。代码已接通，默认关闭图宿主；不等于已将业务 AgentVersion 切换到 LangGraph。

## 部署边界

- Java 保管模型/MCP 凭据、Run 权威、许可、回执和最终业务提交。Python 只有 Worker 凭据、短期 Run capability 和独立检查点库凭据。
- `POST /internal/agent-dispatch/v1/{claim,renew,renew-batch,defer,acknowledge}` 使用独立 Bearer Worker 凭据和 `X-Graph-Worker`。不能用 Cookie、`X-Workflow-Token` 或 Run capability 代替。
- `POST /internal/agent-runtime/v1/{authorize,invoke}` 使用当前 Run capability；Worker 凭据不能用于工具/模型执行。其他内部路径/方法默认拒绝。
- 入口必须 HTTPS，且部署侧限制网络来源。只有双方显式打开 loopback 开关才允许 `127.0.0.1`/`::1` HTTP；不接受任意 HTTP。反向代理必须正确处理 TLS 终止，不能信任来自公网的伪造 forwarded 标头。
- Worker 凭据是可信工作池的凭据，可以领取该宿主 artifact 的跨租户任务，不是租户 API Key；不得交给浏览器或模型。轮换时排空 Worker，更新两端凭据后再启动；尚无双密钥无中断轮换。

## 配置与启动

Java `application.yml` 提供显式环境变量：

| 环境变量 | 含义 |
| --- | --- |
| `WORKFLOW_GRAPH_ENABLED` | 默认 `false`；`true` 才装配宿主和账户管理 API |
| `WORKFLOW_GRAPH_WORKER_TOKEN` | 独立随机凭据，32–256 个允许字符；由受控部署注入，不写入仓库 |
| `WORKFLOW_GRAPH_ARTIFACT_DIGEST` | 已部署图的 64 位小写 SHA-256；必须与冻结绑定、Worker 一致 |
| `WORKFLOW_GRAPH_LEASE_SECONDS` | 默认 90，允许 30–120；不超过业务 deadline |
| `WORKFLOW_GRAPH_LOOPBACK_HTTP` | 默认 `false`；仅本机隔离联调使用 |

Python 同样由部署注入 `GRAPH_GATEWAY_URL`（HTTPS origin，无路径）、`GRAPH_WORKER_TOKEN`、`GRAPH_ARTIFACT_DIGEST`、`WORKFLOW_LANGGRAPH_CHECKPOINT_DSN`。可选 `GRAPH_WORKER_ID`、`GRAPH_WORKER_SLOTS`（默认 4，范围 1–32）、`GRAPH_LOOPBACK_HTTP`（默认关闭）。

```powershell
uv sync --project agent-runtime-langgraph --frozen
uv run --project agent-runtime-langgraph python -m workflow_agent_runtime
```

不要把凭据写到启动命令参数或日志。官方检查点 schema 由部署迁移账号调用 `initialize_checkpoint_store`，Worker 不自动建表、不连接业务库。活动图各占一个检查点连接；slots 必须小于连接预算并留出维护余量。CLI 只记录脱敏状态/错误，不输出服务响应或 DSN。

控制面单次 HTTP 预算 2 秒，poll 间隔 0.5 秒，续租间隔 10 秒；每次最多领取一个，续租最多 32 个一批。失败立即在本地阻断后续调用，已经发出的 HTTP 不伪装取消。批量续租部分失败时对应执行链失去资格；响应丢失保守停止，等待数据库租约恢复。操作系统 DNS 阻塞的硬截止仍须部署验证，不能承诺 Python socket timeout 覆盖 DNS。

## 全平台准入：V68

`agent_run_admission` 计数所有 NATIVE/LANGGRAPH 的 `QUEUED/RUNNING/PAUSED`。默认平台 1000、每租户 100；这是未完成任务数量，不是每秒速率或模型在途额度。

- V68 在同一迁移事务先锁 `agent_run` 写入，回填基线，再建立 AFTER 行触发器，避免回填期间漏记新 Run。必须保持 Flyway PostgreSQL 事务模式；安排维护窗口评估锁等待，不手动拆开运行 SQL。
- 创建、终态重试、终态释放、删除均与业务事务一起提交/回滚。`ON CONFLICT DO NOTHING` 不计数；暂停不释放；图 `defer/ack` 不代表业务终态，不释放 Run 准入。
- 超额 SQLSTATE `PWA01` 映射 HTTP 429，包括 Flowable/业务异常包装；失败的流程推进事务回滚，不保留“有等待节点却没有 Run”的半成品。
- SECURITY DEFINER 函数只操作触发器所属 schema 中的配额行，固定 `search_path`；普通运行角色不能读取全平台计数。只授予所需业务表权限，不授予禁用触发器、TRUNCATE、DDL 或超级用户权限。
- 调低上限不会取消现有任务；现有量超标时停止新增，等待自然释放。`GLOBAL.tenant_limit` 仅决定新租户默认值，已有 `TENANT:<code>.hard_limit` 独立配置。限额调整目前走受控 DBA 变更，无 UI；不能把 `used` 当手工调节旋钮。
- 全局计数行是短事务串行化点。单元/小并发机制通过不代表高负载容量；多行业务事务仍需既有事务回滚/重试策略，不能持有计数锁进行外部 HTTP。

V67 `max_outstanding` 仍是图接管子额度。V68 覆盖全平台接收，**不将原生 Worker 的活跃并发或所有原生外呼自动纳入图许可**。

## Checkpoint 残留锁处置边界（设计补充，未上线）

发现 Java 租约已过期但图持续 RuntimeBusy 时，按 [接入方案 §7.1](../architecture/langgraph-runtime-adoption-design.md#71-租约过期但-checkpoint-会话锁未释放2026-11-16待实现) 核验，禁止直接按 PID 批量终止连接。先撤销旧执行权并协调退出；会话身份无法确证、控制面不可达或存在 PID 复用竞态时转数据库运维。终止旧会话不代表外部请求已经取消，未知调用许可不得自动释放。当前没有完整身份登记和安全终止控制器，不提供可直接执行的 kill 命令，也不把上述方案列为已验收运维能力。

## 共享账户与连接器配置

`GET/PUT /agent-runtime/resources` 使用现有业务认证，只允许 `PLATFORM_ADMIN`；浏览器 PUT 必须带合法 CSRF Token。PUT 是事务性 upsert，不删除未列出的配置。当前有界管理快照最多 1000 个资源、1000 个绑定，累计越界也拒绝，不能通过多个小 patch 使读取失效；更大目录须先实现分页管理契约。响应读取和请求采用普通管理 API 的 camelCase，内部 dispatch 协议采用 snake_case。

```json
{
  "resources": [{"key": "account:ark-shared", "maxCalls": 4}, {"key": "connector:directory", "maxCalls": 2}],
  "bindings": [
    {"tenant": "default", "kind": "PROVIDER", "target": "12", "resource": "account:ark-shared"},
    {"tenant": "tenant-b", "kind": "PROVIDER", "target": "35", "resource": "account:ark-shared"},
    {"tenant": "default", "kind": "CONNECTOR", "target": "8", "resource": "connector:directory"}
  ]
}
```

`PROVIDER.target` 是 Provider ID，`CONNECTOR.target` 是连接器 ID（不是版本/工具快照 ID）；内置工具用 `BUILTIN` + tool code。资源键只存可信账户别名，不存 API Key。共享真实账户的逻辑 Provider 必须归并同一别名。运行时从已发布 AgentVersion 或当前租户已发布 MCP 快照反查，不接受 Worker 自选资源；缺映射失败关闭。

配置写入和新调用领取使用同一策略锁。旧资源仍有 ACTIVE/QUARANTINED 时，换绑返回 `RESOURCE_BUSY`/409，不能借换名绕过未知请求。缩小 maxCalls 只阻止新增。每次成功变更在同一事务写 `agent_graph_resource_audit` 的递增 revision、操作人、配置 patch 和时间；失败不留成功审计，审计禁止更新/删除。不复制模型凭据，不提供任意字符串释放未知许可的管理接口。

## V75 物理调用恢复

先应用 V75，再启动新版图宿主。新许可分别持久化发送与返回事实：未发送且已被发送屏障保护的许可可以回收，已返回调用不依赖业务校验成功才能释放；已发送但结果未知仍隔离。旧许可 tracking_version=0 不回填、不因缺少新事实而自动释放。

宿主自动装配容量恢复 Worker，每轮最多 100 条，`workflow.agent.graph.call-recovery-interval-ms` 默认 1000；与完成收尾共用 graphCompletionScheduler。持久停止、换代和失租触发保守恢复，不等于远端取消。受限角色需按现有部署流程取得新增事实表权限。状态矩阵、迁移及故障窗口见[物理调用恢复验收](../quality/graph-physical-call-recovery-regression.md)。

## 上线门禁与仍开放项

宿主不公开 `reserve`：只能通过受信平台版本路由接管尚无 Attempt 的 QUEUED Run。当前业务版本仍为 NATIVE；artifact 筛选和 V69 图观察→业务结果/Flowable Outbox 的最终接纳已接通，但新 AgentVersion 后端发布路由和完整节点等价尚未开放。

## V69 最终结果接纳与升级

- 不修改 V65–V68。应用 V69 前停止旧图宿主和 Worker；旧预约没有完整业务绑定，`business_identity` 保持 NULL，不从当前字段伪造历史授权。先核对这些预约并通过受信运维取消；需要执行时提交新 Run。旧预约不会被新 Worker 领取，也不能自动验收。该门禁不影响 NATIVE Run。
- 开启图宿主同时装配独立 `graphCompletionScheduler`（单线程，每轮最多 20 条），`workflow.agent.graph.completion-interval-ms` 默认 1000。每次事务只领取一个 Run，跨实例 `SKIP LOCKED`，单事务超时 10 秒，不做模型/工具网络调用。
- `agent_graph_dispatch.status=ACKNOWLEDGED` 仅表示收到图观察；`completion_status=COMMITTED` 才表示 Run/Attempt/状态历史/完成 Outbox 同事务提交。Flowable 是否处理仍看 `agent_run.workflow_resumed_at` 与 Outbox/Inbox，不能混为一个成功状态。
- 验收基础设施失败：ACK 保留，`completion_failures` 递增，`completion_reason=GRAPH_COMPLETION_RETRY`，`available_at` 持久退避 1–60 秒；数据库完全不可用时不能写退避，恢复后扫描已有 ACK。
- `BLOCKED` 表示绑定/回执证据不可信，`DISCARDED` 表示 Run 已终态。两者不覆盖 Run、不发送新完成事件；先查看 `completion_reason`，不要 SQL 清空不可变标记或重新调用模型掩盖问题。确认原件与身份后按受信运维取消/新建执行处理，自动修复入口尚未提供。
- `inspect` 仓储投影包含完成状态、原因和时间；浏览器运维页/控制面查询 API 尚未接入。只向运维展示稳定原因，不输出候选、原凭据或 capability。

只读核对（在受信运维数据库会话执行，租户边界仍适用）：

```sql
SELECT d.tenant_code,d.run_id,d.status,d.completion_status,d.completion_reason,
       d.completion_failures,d.available_at,r.status AS run_status,r.workflow_resumed_at
FROM agent_graph_dispatch d JOIN agent_run r ON r.tenant_code=d.tenant_code AND r.id=d.run_id
WHERE d.status='ACKNOWLEDGED'
ORDER BY d.available_at,d.run_id;
```

后续按 G0/G2 完成未知请求可信对账、控制面可观测性、加权/预留、异步与连接预算、多实例持续压力；按 G1 完成路由/摘要/READ_RESULT/有限修复，再做 P-17/Flowable 业务回归，最后 G3 切换排空。具体证据见[并发回归记录](../quality/graph-capacity-regression.md)。


## V2 部署增量（2026-11-20）

V70 增加独立的 v2 回执表。先部署兼容的 Java/Python，再显式为新 Run 创建 schema 2 绑定；默认 reserve 仍为 v1。dispatch v1 不变，Python 根据冻结 binding 选择 runtime v1/v2 入口。受限运行角色须按现有部署流程取得新增 RLS 表的必要权限。详见[部署和验收边界](../quality/graph-v2-intent-regression.md)。


2026-11-20 后续治理切片：已补 v2 Run 次数预算、不可逆停止与独立无模型收尾。实现范围、V71 兼容策略及 Token/费用剩余边界见[预算与停止验收](../quality/graph-v2-budget-stop-regression.md)。


2026-11-20：V72 增加 v2 冻结重复检测策略和不可变判定账本；动作/结果重复及短周期命中后返回既有 `RUN_STOPPED`，无需新 wire 字段。策略声明只允许可信 Java 发布入口设置，不能从图请求覆盖；完整边界见[重复与短周期验收](../quality/graph-v2-loop-regression.md)。


### V73 CHECKLIST 与纠偏发布注意

先部署 V73，再运行引用新表的宿主版本。只有新 Run 的发布版本显式包含 `x-graph-checklist` 时启用；既有 Run 不回填。Python artifact 需要包含 prepare_correction 节点，并按既有 artifact 路由发布，不将新节点强加到旧检查点。

检查纠偏状态使用 agent_graph_correction 与 agent_graph_correction_result；只有许可、没有结果表示尚未提交纠偏回执。不要删除许可、归零预算或手改检查点来重置机会，恢复同一 Run/意图，或等待原 deadline 的停止收尾。完整边界见[验收记录](../quality/graph-v2-checklist-correction-regression.md)。本轮未执行生产迁移、artifact 发布或业务版本切换。


### V74 PLANNED 首版

先部署 V74 再启用包含规划节点的新 artifact；仅对已审核并明确声明 x-graph-planning 的新版本开启。策略、裁决、动作绑定分别存于 agent_graph_plan_policy、agent_graph_plan_verdict、agent_graph_plan_intent。accepted 只是计划验收，不是业务完成；不要删除裁决或修改 revision 重新发起模型。恢复使用原规划 operationId；动态计划切换尚未开放。见[Plan 验收记录](../quality/graph-v2-plan-regression.md)。本轮未执行生产迁移或切换。


### V76 一次计划修订

先应用 V76 并部署理解 REPLAN_REQUIRED/PLAN_SWITCH_PENDING 的 Java 和图 artifact，再为新版本显式声明 `x-graph-planning.version=2`。旧策略 version=1 保持原路径。新版仅允许循环触发的一次 revision 1 → 2 修订，使用原有 Run 唯一纠偏许可。PLAN_SWITCH_PENDING 表示仍有未释放调用，正式宿主会转换为稳定 RETRY 等待以恢复原验证请求；不要重新生成计划或人工清空隔离账本。无效/预算不足修订直接拒绝并无模型收尾。迁移、兼容与恢复细节见[修订验收](../quality/graph-plan-revision-regression.md)。


### V77 摘要与读取发布注意

先应用 V77，再部署支持 READ_RESULT、CONTEXT_REQUIRED 和三个新增图节点的宿主与 artifact；按既有 artifact 路由保留旧检查点入口。只为审核后的新版本显式配置 x-graph-memory，历史绑定不回填。新增表仍需要部署账号按现有 RLS 权限流程授权。

agent_graph_memory 保存摘要父子链和来源；agent_graph_read_intent/read_result 保存本地读取意图与回执。不要删记录、重置预算或更改原逻辑 ID 来绕过失败。TOO_LARGE/MISSING_FIELD/NOT_JSON 是本地读取状态；权限拒绝、预算停止与未知外呼分别沿用授权、无模型收尾、V75 隔离路径。详细配置与验收见[压缩与回读记录](../quality/graph-memory-regression.md)。本轮未执行业务库迁移或 artifact 发布。
