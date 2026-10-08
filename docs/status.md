# 下一步计划与当前状态

更新时间：2026-10-06

本文是“下一步计划”唯一入口。长期原则见[长期设计总览](architecture/long-term-design.md)，具体缺陷见[待修复问题](quality/known-issues.md)。

## 一、当前基线

### 平台与工作流

- 已完成模块化单体拆分：`auth-engine`、`workflow-engine`、`rules-engine`、`agent-engine`、`knowledge-engine`、`platform-migrations`、`workflow-boot`。
- 已具备注册登录、HttpOnly Cookie、CSRF、租户/角色/权限、流程版本、派单规则版本和基础审计。
- 已具备流程启动、审批、驳回、转办、终止、实例查询、参与人解析和流程图展示。
- 已建立 PostgreSQL RLS、租户上下文、Redis 锁和可靠兜底命令。

### Agent 首个纵向闭环

- 已完成 AgentDefinition/AgentVersion、Provider、AgentRun/Attempt/Step/Checkpoint 和模型调用审计；平台 Agent Worker 在每个逻辑步骤完成后持久化幂等 Step/Checkpoint，传统执行器保留完成后兼容写入。
- Agent 定义已提供受权限保护的删除接口和前端入口；仅未发布、无运行历史且未被流程引用的草稿定义可物理删除，已发布定义保留以保证流程和审计可追溯。
- 已完成正式 AgentRun 执行链路：Provider 调用、Schema 校验、基础结果策略、重试、租约心跳、Recovery、Outbox/Inbox 和 Flowable 恢复；平台 Agent 的逻辑步骤 Checkpoint 现在包含恢复游标和受限上下文，并在新 Attempt 读取。
- 已增加 `PLATFORM_AGENT` 执行模式的首个切片：计划阶段与执行阶段形成受控循环，仍复用同一 AgentRun 和流程恢复链路；当前仅允许后端显式注册的工具。
- 已增加显式 `AgentTool`/`AgentToolRegistry` 应用端口，结构化 `TOOL_CALL` 只能调用后端注册工具，未注册工具会拒绝；AgentVersion 已冻结工具代码集合，BPMN 节点只能继续收紧，当前尚未开放通用 HTTP、脚本或租户自定义代码工具。
- Provider 请求现在携带当前租户已授权工具的名称、描述和输入 Schema；Provider Adapter 通过能力契约声明原生 Tool Calling 支持，Chat Completions 使用结构化工具调用，不支持原生工具的 Responses 兼容端点使用受控文本工具协议。模型不能凭 Provider 凭证获得工具权限，工具仍由 Runtime 注册表和租户授权共同决定。
- 已落地首个只读业务工具 `agent_run_status`，工具定义、租户授权、输入 Schema、稳定幂等键、带租约的数据库原子抢占和执行审计由 PostgreSQL 管理；工具输出不直接修改流程或 AgentRun。
- 已落地真实业务只读工具 `workflow_process_context`：Agent 可按租户读取流程实例元数据、当前人工任务和脱敏业务变量，继续复用 AgentRun/Step/审计链路；工具不能推进或修改流程。知识检索另有独立 `knowledge_search` 端口和授权边界。
- 已建立 `knowledge-engine` 的 Evidence 多态契约、授权范围求交、检索 Trace、`RetrievalProfileVersion` 模型、知识源/文档/索引/摄取任务生命周期模型和 `knowledge_search` 只读工具端口；RAG-2 已新增文档快照、确定性 Chunk 切分、索引构建端口、`knowledge_chunk` 全文检索表、PostgreSQL 文档/索引/Trace 适配器和租约 Worker。当前已增加 AgentVersion 到 RetrievalProfile 的数据库绑定、提交人随 AgentRun 快照传递及主体知识范围授权表/适配器，并新增租户边界内的 Profile 草稿/发布、文档入库与摄取任务、主体范围授权管理 API；前端已提供主体范围授权入口，避免运行时因缺少主体授权而只能失败。V47 已为 Profile、文档、摄取查询和范围授权建立独立权限，前端已提供 ACTIVE 索引选择、Profile 草稿/发布和文本/Markdown 文档提交入口；workflow-boot 已接入按启用租户轮询的摄取调度器，任务会通过租约 Worker 有界领取并执行。V48 已在已有检索应用、Profile 绑定和主体范围授权边界内恢复 `knowledge_search` 只读工具，AgentVersion 可绑定已发布 Profile 后进入真实检索链路；本地浏览器已验证全文检索证据回注、流程路由和后续审批完成。pgvector/混合检索、Grounding 和离线评测仍未接入。
- 已开放 `PLATFORM_AGENT` 前端配置；流程运行时自动注入受控 `processInstanceId`，模型无需把流程 ID作为业务输入，手动测试仍支持显式传入。
- 已完成 MCP-1/MCP-2 只读配置与运行闭环：Connector/Version、目录发现与审核发布、工具 Schema 快照、AgentVersion 绑定、HTTPS Streamable HTTP 的 initialize/tools/list/tools/call、MCP Adapter 与现有 Registry/租户授权/审计链路连接；前端已提供连接器、目录审核、草稿删除和 Agent 版本工具绑定入口；确定性 HTTPS 协议测试覆盖会话、initialized 通知、SSE 多事件、批量响应、目录发现和工具调用。本地浏览器已验证“模型选择工具 → MCP 调用 → 返回 `count` → AgentRun 成功 → Flowable 完成”。另已补齐 MCP 调用级 Redis 租户令牌桶、并发硬上限、连接器熔断、有界 TTL 会话复用、凭据指纹轮换和 Worker 接管后的 Checkpoint 恢复测试。当前仍未宣称生产级出站安全、确定性 MCP Server 容器故障注入和多实例压力验收完成。
- 前端状态展示已收敛到公共 `StatusBadge`/`TableTagCell` 契约：状态、版本、分类和筛选标签分别使用稳定变体；标签不再在组件内部截断，原始状态码通过悬浮提示保留；流程定义、Agent、工具目录、成员角色、租户、派单规则、参与人和审计页面统一状态列对齐。
- 重试已使用可注入的指数退避 + 有界随机抖动，最终 `available_at` 在同一事务中持久化。
- 已增加类型化失败模型与恢复决策账本：Provider 临时/永久故障、输出/输入契约、工具协议、结果策略、配置、业务拒绝、截止时间和未分类异常均在边界处明确归类，再由恢复策略选择重试、修复、人工介入或终止；运行详情可查询安全诊断信息、Trace ID、Attempt、Step 和恢复决策。
- 已完成首节点及审批后 Agent 输入契约：后端按真实路径生成字段，前端动态渲染，命令边界再次校验必填输入。
- 已提供租户安全的流程实例 AgentRun 关联查询：`GET /agent-runs/process-instances/{processInstanceId}`，外部系统可以跟踪执行状态而不接触内部状态机。
- 已提供受权限保护的失败运行处置命令：修复 Provider、凭证或 Agent 配置后，可通过 `POST /agent-runs/{runId}/retry` 显式开启 30～3600 秒的新执行窗口并创建新的 Attempt；流程仍停留在原 Agent 节点，只有新 Attempt 成功后才恢复 Flowable，不能无限延长原始运行。
- Agent 节点失败策略已形成三种明确语义：`HOLD_FOR_OPERATIONS` 保持在当前节点等待运维，`CONTINUE_EMPTY` 携带失败上下文继续，`MANUAL_REVIEW` 写入稳定的 `agentReviewRequired`、Run/结果状态与错误码变量并恢复 Flowable，由 BPMN 网关进入显式人工复核任务；不再由 Agent Runtime 暗中创建另一套人工任务状态机。
- 输入映射首版只允许标量、对象字段和整个数组；输出映射支持数组索引和通配投影。

### 工程质量基线

- 已接入 Maven Enforcer、Checkstyle、SpotBugs、JaCoCo、ArchUnit、OpenAPI lint、路由覆盖和兼容性检查。
- 前端已接入格式、Lint、类型检查、单元测试、构建和 Playwright E2E。

## 二、当前阶段与验收

| 优先级 | 工作项 | 当前状态 | 下一步验收 |
| --- | --- | --- | --- |
| 已完成 | 容器化集成测试基线 | 本机 Docker Engine 29 已恢复；父构建固定 docker-java API 1.44。`mvn verify` 共执行 271 个测试，0 失败、0 错误、0 跳过，5 个 PostgreSQL/Redis/Flowable/Flyway/RLS/HTTP 安全基础设施套件通过强制门禁 | 在 CI 持续运行并归档 Surefire、JaCoCo、SpotBugs 报告；真实强杀和多实例压力仍按专项验收 |
| P0 | 生产可观测性 | 已有租户隔离的 Runtime 健康摘要、低基数运行/积压/暂停/恢复/死信/租约指标、Prometheus 告警、Runbook、流程操作审计和前端状态化处置入口 | 接入 Alertmanager，并在容器与多实例环境验证告警、审计和处置联动 |
| P0 | 流程操作审计 | 基础写入与租户隔离查询 API 已有，支持事件、实例、Trace、时间范围和分页 | 通过容器化环境验证全链路事件可追溯，并补齐规则命中等覆盖 |
| P1 | 租户纵深隔离 | 平台业务表已启用强制 RLS | 完成 Flowable 内部表评估和跨租户负面测试 |
| P1 | API 契约治理 | 路由覆盖、成功响应模型和 Agent 运行接口错误响应已继续收敛 | 全部认证接口错误响应、DTO、分页模型和客户端类型生成完整 |
| P1 | Agent Runtime 生产可靠性 | MODEL_ONLY 与 PLATFORM_AGENT 两阶段切片、失败分类、抖动重试、子步骤 Checkpoint、取消及协作式暂停/恢复已落地；浏览器已验证排队暂停、恢复和 Worker 重启后重新领取；当前每实例使用单线程 Heartbeat 调度，Checkpoint 仍以有界文本上下文和最后一次工具结果为主 | 将 Heartbeat 演进为有界多线程或批量续租并补齐积压指标/高并发门禁；实现版本化 Checkpoint Context 压缩与引用化恢复，再完成真实强杀/租约过期/跨实例压力、出站策略、公平调度、Guardrail 和高风险写工具确认 |
| P1 | Agent 交互扩展 | 输入契约和流程实例运行查询已完成首个切片 | 版本化表单、复杂对象/数组控件、外部幂等提交和待补录任务完成 |
| P1 | LangChain4j 基础设施复用 | Chat Completions、Responses API 与 MCP 协议处理已默认切换到 LangChain4j，原适配器保留为显式回滚开关；平台端口、治理和可靠执行语义未变 | 继续验证文档解析、Embedding、向量存储和 Rerank 适配；扩充真实 Provider 与 MCP Server 兼容矩阵 |
| P1 | 受治理 RAG 最小闭环 | Evidence 多态契约、授权求交、生命周期模型、Trace、确定性 Chunk 契约、全文检索表、Profile 模型、PostgreSQL 适配器、专用权限、摄取调度和 Agent 绑定/只读检索链路已落地 | 完成 pgvector/混合检索、Grounding、评测、摄取恢复和多 Retriever 兼容验收 |
| P1 | 权威组织关系解析 | 方向已与 RAG 拆分，尚未建立 `organization-engine` 和组织关系数据模型 | 完成组织实体/关系有效期、有限跳查询、参与人策略、空/多人/停用兜底、路径审计和跨租户负面测试 |
| P1 | 受治理 MCP 最小闭环 | MCP-1/MCP-2 本地浏览器纵向闭环和确定性 HTTPS 协议测试已完成，绑定原子性、类型化错误、有界响应、租户限流/并发配额、连接器熔断、会话复用、凭据轮换和 Checkpoint 接管测试已收口；确定性 MCP Server 容器测试、SSRF/DNS 重绑定和多实例压力验收未完成 | 完成真实 MCP Server 容器故障注入、出站安全和多实例租户治理验收后，再称为生产可用只读 MCP 闭环 |

## 三、下一阶段顺序

1. 完成剩余 P0 生产运营闭环：接入 Alertmanager，补齐流程全事件审计覆盖，并验证告警到处置的回放链路。容器化集成测试基线已经完成，不再作为待办重复开发。
2. 并行完成 Agent Runtime 生产故障验收：先将每实例单线程 Heartbeat 演进为有界多线程或批量续租，增加续租延迟、调度积压和意外丢租指标，并验证 Worker 强杀、租约过期、运行中外部调用边界暂停和多实例压力；同时把 Checkpoint 的文本截断升级为版本化 Context 压缩与引用化恢复，继续 RLS 深度验证、API 契约和核心覆盖率。
3. RAG 作为下一条主功能线：Profile/文档/摄取/主体授权和 `knowledge_search` 已闭环；优先通过稳定端口复用 LangChain4j 的 Embedding、向量存储与 Rerank，完成 pgvector/全文混合检索、Grounding、评测和摄取恢复。
4. MCP 进入生产强化而非重新开发协议：LangChain4j 已接管 initialize、工具目录、JSON-RPC 关联和 tools/call；下一步完成确定性 MCP Server 容器故障注入、HTTPS 出站安全和多实例租户治理压力测试。人工确认与未知结果处置完成后再实施 MCP-4 写工具。
5. 完善 Agent Runtime 业务门禁：版本化 Guardrail、成本预算、租户业务规则、公平调度和高风险写工具人工确认。
6. 组织关系解析作为独立并行路线，使用 `organization-engine` 和 `OrganizationGraphQueryPort`，不得并入 RAG 检索编排，也不得由模型决定审批参与人。
7. 最后扩展任务中心、表单、通知、委托、SLA、完整工具治理和证据驱动自治。

### LangChain4j 采用边界

引入 LangChain4j 的目标是减少通用 AI 基础设施重复建设，不是替换 Workflow Agent 的领域模型和可靠运行时。

优先复用：

- Chat Model 与不同 Provider 的协议适配；
- Function Calling、工具 Schema 和结构化输出基础能力；
- MCP Client 的 Streamable HTTP、SSE 和协议兼容处理；
- 文档解析、Embedding Model、Embedding Store 和 Rerank 适配；
- 多 Retriever 查询、聚合等可替换 RAG 基础组件。

平台继续拥有：

- `AgentDefinition`、不可变 `AgentVersion` 及租户权限；
- `AgentRun / Attempt / Step / Checkpoint` 运行账本；
- Lease、Heartbeat、Recovery、Outbox/Inbox、幂等和迟到结果隔离；
- Tool Registry、MCP 目录审核、版本冻结、凭据、配额和执行审计；
- RetrievalProfile、知识范围授权、Evidence/Citation/Trace 和 Grounding 门禁；
- Flowable 恢复、人工确认、人工接管和证据驱动自治。

实施规则：

1. LangChain4j 只能通过现有或新增的应用层端口接入，不得让领域和应用层依赖其类型。
2. 优先建立 `LangChain4jMcpClientAdapter`、模型适配器及 Embedding/Rerank 适配器，不一次性重写 Agent Runtime。
3. LangChain4j `AI Services`、`ChatMemory` 和实验性 Agentic 能力不得成为流程状态、运行账本或恢复语义的权威来源。
4. 替换必须保持已有租户、版本、权限、审计、Checkpoint 和 Flowable 结果契约不变。
5. 适配验证至少比较协议兼容、故障分类、恢复行为、可观测性、依赖稳定性和删除的自研代码量；不能只因框架存在就迁移。
6. 新增模型协议、MCP 传输、Embedding、向量库或 Rerank 能力时，应先评估 LangChain4j；无明确缺口不得继续手写同类通用组件。

当前默认实现与回滚边界：

- `WORKFLOW_AGENT_PROVIDER_OPENAI_ADAPTER=langchain4j`：默认使用 LangChain4j Chat Model；`/chat/completions` 使用 `OpenAiChatModel`，`/responses` 使用 `OpenAiResponsesChatModel`；设为 `legacy` 可回滚。两条协议均复用平台有界 HTTP、超时和类型化失败边界。
- `WORKFLOW_AGENT_MCP_CLIENT=langchain4j`：默认使用 LangChain4j MCP Client；设为 `legacy` 可回滚。
- MCP 的 JSON-RPC、初始化、工具发现和调用语义由 LangChain4j 维护；平台只保留 HTTPS、凭据注入、硬响应上限、超时、会话生命周期和故障分类等基础设施约束。模型规划与后续工具调用共享同一个绝对截止时间，每一步只获得剩余预算，避免按最大步骤数机械切分导致正常调用提前超时。

## 四、明确暂不做

- Dify 类通用 Agent 内部流程画布。
- 租户上传任意 Java、脚本、插件或任意目标 HTTP 工具。
- 租户配置任意本地 MCP `stdio` 命令，以及未经审核即动态使用 MCP 工具。
- 无边界的多 Agent 自主协作、复杂记忆和开放式工具生态。
- 使用 LangChain4j 高层 Agentic/Memory 替代平台持久化运行账本、Flowable 编排或人工治理边界。
- 在组织、表单和任务中心基础能力之前扩展高级企业功能。

## 五、完成判定

首个纵向闭环不等于 Agent MVP 完成。只有执行、恢复、幂等、权限、审计、取消、人工交互和 Flowable 集成全部通过容器化集成测试，才可将 Agent MVP 标记为完成。

最近一次 LLM/MCP/RAG 纵向验证（2026-09-26）：

| 场景 | 流程实例 | 结果 |
| --- | --- | --- |
| LLM | `7350d588-b952-11f1-afff-00155d46a074` | 模型调用成功，AgentRun 完成，Flowable 流程结束 |
| MCP | `612dd907-b951-11f1-afff-00155d46a074` | 模型选择只读 MCP 工具，返回 `{"count":5}`，AgentRun 与流程均完成 |
| RAG | `8a814331-b952-11f1-afff-00155d46a074` | `retrievalStatus=FULL`，命中 `employee-handbook.md` 第 1 版，按证据路由到直属负责人并完成审批 |

以上证明本地配置与运行纵向链路可用，不替代专项容器故障注入、Worker 强杀、跨实例、出站安全和质量评测门禁。

最近一次工程质量基线（2026-10-06）：后端 9 个 Reactor 模块和 271 个测试全部通过，0 失败、0 错误、0 跳过；5 个必需基础设施集成套件通过门禁。前端格式、Lint、类型检查、39 个单元测试、生产构建、包体预算和 14 个 Playwright 场景通过。

### Agent Runtime 近期加固

- 已提供租户范围 Runtime 健康摘要，统一展示状态分布、最老排队时间、过期租约和需人工介入数量；定时健康指标只使用状态、结果等低基数标签，不把 tenantCode、runId 或 traceId 写入指标标签。
- 已实现协作式暂停/恢复：排队运行立即暂停，运行中请求在模型/工具调用后的持久化安全边界生效；恢复保留最后完整 Checkpoint 并重新排队，暂停、恢复、取消和重试均写入状态历史与操作账本。
- 前端运行记录页根据实际状态只展示合法动作，并在详情中统一呈现失败分类、错误码、Attempt、Step、恢复决策和安全诊断；本地浏览器已验证 `QUEUED -> PAUSED -> QUEUED -> Worker 重新领取` 以及输入契约失败的可定位信息。
- 已补充 Agent Runtime Prometheus 告警与逐项 Runbook；Alertmanager 接线、容器故障注入和真实多实例演练仍属于发布验收，不以本地浏览器验证替代。
- Provider 适配器通过能力契约声明协议和原生 Tool Calling 能力，运行时不再直接根据凭证推断能力；Responses 兼容端点继续使用受控文本工具协议。
- Provider HTTP 错误只提取有长度限制且脱敏的诊断摘要，完整原始响应不落库；恢复决策账本会向授权运维界面提供可操作上下文。
- Agent Worker 领取查询支持租户级并发上限，避免单一租户占满所有执行槽位；上限通过 `WORKFLOW_AGENT_WORKER_MAX_RUNNING_PER_TENANT` 配置。
- 人工重试除状态历史外，写入独立的 `agent_run_operation` 操作账本；V31 同时为 Agent 操作和已存在的 Flowable 租户表补充纵深隔离策略。
- 结果策略支持显式 `resultStatus` 业务结果封套，只有 `SUCCESS` 才允许恢复流程；空结果、部分结果和业务拒绝均保持终止语义。
