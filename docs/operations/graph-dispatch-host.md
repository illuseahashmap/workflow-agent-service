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

## 上线门禁与仍开放项

宿主不公开 `reserve`：只能通过受信平台版本路由接管尚无 Attempt 的 QUEUED Run。当前业务版本仍为 NATIVE；artifact 领取筛选已接通，但新 AgentVersion 后端发布路由、图观察→业务结果/Flowable Outbox 的最终接纳、完整节点等价尚未开放。

后续按 G0/G2 完成未知请求可信对账、控制面可观测性、加权/预留、异步与连接预算、多实例持续压力；按 G1 完成路由/摘要/READ_RESULT/有限修复，再做 P-17/Flowable 业务回归，最后 G3 切换排空。具体证据见[并发回归记录](../quality/graph-capacity-regression.md)。
