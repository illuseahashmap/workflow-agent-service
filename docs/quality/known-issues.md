# 待修复问题与下一步整改

更新时间：2026-10-06

本文只保留尚未关闭的问题。已完成内容见[项目状态总览](../status.md)，不可破坏的架构约束见[长期设计总览](../architecture/long-term-design.md)。历史问题不在本文重复记录。

## P0：发布与生产基线

### P0-2 生产可观测性与流程审计仍未完整闭环

- 当前：已有 Trace ID、结构化日志、健康检查、状态历史、恢复决策和运维操作账本；`GET /workflow/management/audit/operations` 支持按当前租户、事件类型、流程实例、Trace ID、时间范围和分页查询流程操作审计。
- 当前新增：`GET /agent-runs/operations/overview` 提供租户范围状态分布、最老排队时间、过期租约和需人工介入数量；Micrometer 定时暴露运行、积压、暂停、恢复、死信和租约健康指标，禁止 tenantCode、runId、traceId 等高基数标签。
- 当前新增：前端运行记录页展示健康摘要、失败分类和状态化操作；Prometheus 规则与 `docs/operations/agent-runtime-alert-runbook.md` 已覆盖积压、过期租约、暂停堆积、失败/死信和恢复异常。
- 缺口：Alertmanager 尚未接线；容器化环境下流程全事件追溯、告警触发到处置联动及多实例指标准确性仍未演练。
- 验收：可按租户、流程实例、AgentRun、Attempt、操作人和 Trace ID 定位一次失败，并回放关键状态变化。

## P1：隔离、可靠性与契约

### P1-0 历史数据库可能记录过临时修改版 V37

- 当前：仓库已恢复不可变的原始 V37，并将历史 RUNNING 记录处置迁移到 V38；新数据库和只执行过原始 V37 的数据库可以直接迁移。
- 风险：如果某个数据库曾在临时修改版 V37 存在期间执行过迁移，Flyway 会因历史 checksum 与当前 V37 不同而拒绝启动。
- 处理：必须由 DBA 按[ V37 checksum 兼容处理手册](../operations/flyway-v37-checksum-recovery.md)确认备份、结构和执行历史后再 repair；不得盲目 repair、跳过 validate 或再次修改 V37。
- 验收：V37 checksum 与当前文件一致，V38 成功执行，应用启动无 `FlywayValidateException`。

### P1-1 Flowable 内部表 RLS 需要真实版本验证

- 当前：V31 会对迁移时已存在且带 `TENANT_ID_` 的 Flowable 表启用强制 RLS；应用层租户过滤仍保留。
- 缺口：需要在真实 Flowable 表集合上逐表确认租户字段、系统 Worker 授权和跨租户负面场景。
- 验收：无可信上下文默认拒绝；平台管理员和系统 Worker 经过显式授权；跨租户读写全部失败。

### P1-2 OpenAPI 响应契约仍需细化

- 当前：公开路由已有覆盖、lint 和兼容性门禁。
- 缺口：部分接口仍使用通用 `ApiResponse`，错误码、分页、权限要求和前端类型尚未完全从 DTO 契约生成。
- 验收：核心接口的请求、成功响应、错误响应、分页和权限要求可由 OpenAPI 直接验证。

### P1-2a RAG 生产检索闭环仍未完成

- 已完成：`Evidence` 多态契约、应用层授权范围求交、检索 Trace 元数据、知识源/文档版本/索引版本/摄取任务生命周期模型、RLS 表结构和 `knowledge_search` 只读工具边界。
- 已完成：AgentVersion 到 RetrievalProfile 的租户内数据库绑定、已发布 Profile 解析、提交人随 AgentRun 快照传递，以及主体知识范围授权表和应用层求交适配器。
- 已补齐：租户边界内的 Profile 草稿/发布、文档入库与摄取任务创建、摄取任务分页查询、主体范围授权增删查 API；文档写入与摄取任务创建在同一事务内完成，重复提交按租户/来源/内容哈希幂等。
- 已修复：管理适配器统一使用 JDBC `Timestamp` 转换 PostgreSQL `timestamptz`，并为可选分页筛选参数显式声明 SQL 类型；避免知识库列表和文档提交回读因驱动类型转换或空参数类型推断返回 500。
- 已验证：本地浏览器已跑通全文检索、Evidence 回注、Agent 结果和 Flowable 后续审批，运行命中 `employee-handbook.md` 第 1 版且 `retrievalStatus=FULL`。
- 未完成：Grounding 策略、离线评测集、pgvector/混合 Retriever 以及生产级 Trace 查询接口尚未接入；当前已具备按租户轮询的摄取调度、Profile/文档/主体范围管理入口和 PostgreSQL 文档/Chunk/全文检索基础设施，但仍不宣称生产级 RAG 闭环。

### P1-3 Agent Runtime 生产故障恢复验收尚未完成

- 当前：已有 Attempt 隔离、租约心跳、`SKIP LOCKED`、随机抖动、旧 Attempt 迟到结果保护、失败分类和人工重试；平台 Agent 每个逻辑步骤完成后立即持久化 Step 和 Checkpoint，Checkpoint 写入会再次校验当前 Attempt/租约，恢复读取按 Attempt 序号和步骤序号选择。
- 当前已增加受权限保护的活动运行取消命令：会清理租约、终止当前 Attempt、写入状态历史和操作账本；Worker 的迟到完成仍受 Attempt 条件保护。
- 当前已增加协作式暂停/恢复：QUEUED 可立即转为 PAUSED，RUNNING 在安全边界持久化后暂停；恢复保留 Checkpoint 并重新排队，重复命令幂等。浏览器已验证暂停、恢复、后端重启后重新领取和可操作失败详情。
- 当前已验证：MCP Worker 接管能够从持久化 Checkpoint 恢复并重新初始化会话；内存会话不作为恢复事实来源。
- 缺口：真实进程强杀、心跳失效、运行中外部调用边界暂停和跨实例压力测试仍需补齐，现有单元与浏览器恢复测试不能替代容器级故障注入。
- 验收：租约失效或 Worker 被杀后能从最后完整 Checkpoint 恢复；旧 Attempt 不能覆盖新 Attempt；终态操作幂等。

### P1-3a 迁移账号与运行账号的最小权限部署尚未形成标准模板

- 当前：应用支持通过 `WORKFLOW_MIGRATION_DB_URL/USERNAME/PASSWORD` 为 Flyway 配置独立迁移账号，未配置时兼容复用运行数据源；迁移版本唯一性已有自动测试，避免重复版本导致启动期失败。
- 缺口：现有本地数据库的表所有者和运行账号授权并不统一，尚无可直接复用的“迁移账号建表 + 运行账号最小权限 + RLS 策略”初始化模板与升级验证。
- 验收：全新数据库和存量升级均可由独立迁移账号完成，运行账号不是表所有者且仅具备所需权限，Flyway validate、应用启动和 RLS 负面测试全部通过。

### P1-11 工具幂等并发与参数冲突

- 当前：逻辑幂等键为 `runId + logicalStepId + toolCode`，参数哈希独立保存并比较；数据库实现通过带 claim owner、lease、fencing token 的 `INSERT ... ON CONFLICT ... RETURNING` 原子抢占 RUNNING 记录，FAILED 或过期 RUNNING 只有在参数哈希一致时才可由下一次 Attempt 重新领取；参数冲突会被拒绝，未抢占的 Worker 不会重复调用外部工具。V37 回填 V35/V36 窗口内创建的租户授权，V38 将无法证明结果的历史 RUNNING 记录标记为 UNKNOWN。
- 缺口：仍需在真实 PostgreSQL 并发环境补充双 Worker 压力测试，以及 UNKNOWN 副作用结果的人工核验流。
- 验收：同一逻辑步骤参数变化必须拒绝；并发调用最多一个外部执行者；已完成结果可复用。

### P1-9 平台原生工具的不可变目录快照仍需统一

- 当前：AgentVersion 保存不可变工具代码集合；发布阶段会校验平台注册、租户授权、Schema 和执行模式；BPMN 节点只能进一步收紧；运行时执行集合为平台注册工具 ∩ 租户授权 ∩ AgentVersion 集合 ∩ 节点子集，未绑定工具会被拒绝。
- 当前：MCP 已具有 CatalogVersion、ToolSnapshot、指纹、审核发布和 AgentVersion 绑定；已发布 MCP 目录变化不会直接改变历史版本。平台原生工具仍主要冻结工具代码集合。
- 缺口：平台原生工具的 Schema、描述和风险元数据尚未统一为独立不可变目录快照。
- 验收：发布版本引用不可变 ToolSet 快照，工具目录变更不能改变历史运行语义。

### P1-10 knowledge_search 只读闭环验收

- 当前：V48 已在已有检索应用、Profile 绑定、主体范围授权和摄取调度完成后恢复只读工具及租户授权；AgentVersion 必须绑定已发布 Profile，运行时仍由 Tool Registry、Profile 和主体授权共同约束。
- 缺口：pgvector/混合检索、Grounding、Citation 质量门禁和生产级检索评测仍未完成；暂不开放写工具或任意知识范围。
- 验收：已发布 AgentVersion 只能调用绑定 Profile 允许的范围；未授权主体、无 Profile、零证据和检索失败不能伪装为成功并推进流程。

### P1-12 MCP 只读闭环尚未达到生产验收

- 当前：已完成 MCP-1/MCP-2 本地只读纵向闭环，并增加确定性 HTTPS 协议集成测试，覆盖 initialize、initialized、会话标识、SSE 多事件、批量响应、tools/list 和 tools/call；绑定更新要求已发布目录和实际绑定同时成立，目录快照采用独立本地事务提交，MCP 超时/不可用/限流/认证/协议/工具错误已进入类型化恢复分类，响应读取有界且不先完整装入内存；工具凭据只通过服务端引用解析，不进入模型或 API 响应。
- 当前已补齐：MCP 调用入口使用 Redis 原子租户令牌桶、并发配额和连接器熔断；会话缓存有界且带 TTL，凭据指纹变化会使旧会话失效；Worker 接管后从持久化 Checkpoint 继续执行并重新初始化 MCP，会话不作为恢复事实来源。
- 已验证：本地浏览器已完成模型规划、真实 MCP `tools/call`、结果回注、AgentRun 成功和 Flowable 完成，工具结果为 `{"count":5}`。
- 缺口：尚未在 Docker 中跑确定性 HTTPS MCP Server 的真实协议集成；出站 SSRF/DNS 重绑定、私网/元数据地址拒绝、租户治理的真实多实例压力测试、凭据轮换的真实 Secret Provider 接入和 Worker 强杀端到端测试仍未完成。
- 前端现状：工具目录页已具备连接器创建、发现、发布和安全删除未发布草稿的入口；Agent 版本页已提供已发布 MCP 工具的可视化绑定入口。
- 边界：在上述验收前不得称为生产可用 MCP；不得开放写工具、stdio、任意 HTTP 工具或运行时发现即调用。
- 验收：未授权租户、未绑定工具、Schema 不合法、超时、超限响应、MCP 协议错误和重启均有自动化负面测试，且 Flowable 不误推进。

### P1-4 结果策略与 Agent 运行规则仍需租户化

- 当前：已支持 Schema 校验、空结果、部分结果、业务拒绝、内容过滤和显式 `resultStatus`。流程失败策略现已区分保持运维、继续空结果和 BPMN 人工复核；人工复核模式会输出稳定的 `agentReviewRequired`、Run/结果状态和错误码变量，由流程图中的网关进入显式 UserTask。部署解析会沿 Agent 节点下游图验证存在引用 `agentReviewRequired` 的条件路径且最终到达 UserTask。单元测试已覆盖三种分支和错误图拒绝，真实 Flowable/PostgreSQL 同事务路由测试已在 Testcontainers 环境通过。
- 缺口：证据约束、Guardrail、租户业务规则、成本预算和高风险写工具执行前人工确认尚未纳入版本化 Agent 契约。
- 验收：只有通过结构、业务、证据和 Guardrail 的结果才能推进流程，规则版本可追溯。

### P1-5 Provider 出站安全仍需纵深治理

- 当前：默认 HTTPS、超时、响应大小限制及常见 IPv4 内网地址拒绝已存在；开发环境可显式放行本地 HTTP。
- 缺口：IPv6 ULA、CGNAT、保留地址、云元数据地址、DNS Rebinding、私有 Provider 审批和统一出站网关仍需完善。
- 验收：未授权私网、异常端口、DNS 重绑定和元数据地址全部拒绝；授权私有 Provider 有明确审批和审计。

### P1-6 租户与 Provider 公平调度仍需跨实例配额

- 当前：Worker 已有有界线程池、队列和数据库层租户并发上限，默认每租户最多 2 个运行中任务。
- 当前已增加 MCP 外部调用边界的跨实例租户令牌桶、并发硬上限和连接器熔断；该治理不替代 Worker 领取公平调度。
- 缺口：Provider 级配额、统一全局许可证、拒绝与积压指标、动态配额、优先级调度和协调层对账尚未完成。
- 验收：单一租户或慢 Provider 不能长期占满全部执行资源，跨实例压力测试下无无界线程、连接和重复领取。

### P1-7 核心服务与查询仓储仍偏大

- 当前：运维命令仓储已从 Worker 执行仓储拆出，参与人协调和完成事件已有独立边界。
- 缺口：`JdbcAgentRunExecutionRepository`、`AgentRunWorkerServiceImpl`、Flowable 查询/运行服务仍混合 SQL、映射和部分业务编排。
- 验收：查询、命令、映射、状态决策和持久化职责分离；领域规则不复制到 JDBC；核心状态机均有领域测试。

### P1-8 核心覆盖率和故障集成测试低于长期目标

- 当前：JaCoCo 已配置真实 `check`，当前阈值是保守基线；2026-10-06 本地 `mvn verify` 的 271 个测试全部通过且 0 跳过，PostgreSQL、Redis、Flowable、Flyway、RLS 和 HTTP 安全的 5 个必需基础设施套件通过门禁。
- 缺口：核心模块仍需逐步达到 60% 以上；Worker 强杀、租约过期、多实例竞争、重复消息、事务回滚和更多真实 Provider/MCP Server 兼容性场景仍需补齐。
- 验收：关键安全、租约、状态机、恢复和流程推进路径达到更高覆盖率，不能只用 Mock 证明闭环。

## 暂不列入问题的已完成项

- 前端状态标签与表格对齐已统一：`StatusBadge` 负责语义颜色和文案，`TableTagCell` 负责表格内对齐；版本/分类不再复用状态标签的圆角规则，长状态不再被内部省略样式覆盖。
- Provider 能力契约、脱敏错误摘要、恢复决策账本、人工重试操作账本已落地。
- Agent 工具注册、租户授权、输入 Schema、幂等键、执行审计和只读业务工具已落地。
- Agent 定义删除已落地：未发布且无运行历史的草稿可删除；已发布定义通过明确冲突响应保留，不允许破坏流程引用和审计历史。
- Cookie 认证的 CSRF 豁免现在只对已认证的 SERVICE principal 生效；普通 Cookie 用户即使携带任意服务令牌头也仍需 CSRF Token。
- Redis 流程锁已使用可配置的多续租线程、续租失效标记和事务提交前所有权校验；驳回未指定目标时不再猜测第一个 UserTask，而是要求调用方显式指定目标。
- Agent Definition 发布依赖 `AgentToolCatalogPort`，不再直接依赖 Runtime Registry/Policy Repository 的实现；工具真实性校验仍集中在应用端口背后的目录实现。
- OpenAPI lint、路由覆盖、兼容性检查、Checkstyle、SpotBugs、JaCoCo 和 ArchUnit 已接入质量门禁。
- Token Cookie、CSRF、RLS 基础策略、Attempt 复合约束和完成事件幂等链路已完成首版。
