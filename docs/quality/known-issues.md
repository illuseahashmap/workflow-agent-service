# 待修复问题与下一步整改

更新时间：2026-08-25

本文只保留尚未关闭的问题。已完成内容见[项目状态总览](../status.md)，不可破坏的架构约束见[长期设计总览](../architecture/long-term-design.md)。历史问题不在本文重复记录。

## P0：发布与生产基线

### P0-1 容器化集成测试尚未在本机闭环

- 当前：CI 已执行 Docker 检查，并通过 `scripts/check-integration-test-results.mjs` 强制确认 PostgreSQL、Redis、Flowable、Flyway、RLS 和 HTTP 安全测试没有缺失或跳过；本机没有 Docker，因此本地报告不能作为发布验收。
- 验收：发布分支完整 `mvn verify` 和集成测试通过，并归档 Surefire、JaCoCo、SpotBugs 报告。

### P0-2 生产可观测性与流程审计仍未完整闭环

- 当前：已有 Trace ID、结构化日志、健康检查、基础 Agent 指标、状态历史、恢复决策和人工重试操作账本。
- 缺口：Prometheus 查询、告警、失败运行列表、流程发起/领取/审批/驳回/转办/终止/规则命中等操作还未统一接入审计查询。
- 验收：可按租户、流程实例、AgentRun、Attempt、操作人和 Trace ID 定位一次失败，并回放关键状态变化。

## P1：隔离、可靠性与契约

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
- 未完成：PostgreSQL/pgvector Retriever、真实摄取 Worker、Grounding 策略、离线评测集和生产 Trace Repository 尚未接入；当前仍是 RAG-1 到 RAG-2 的工程骨架，不宣称 RAG 闭环。

### P1-3 Agent 状态恢复与多步骤 Checkpoint 尚未完成

- 当前：已有 Attempt 隔离、租约心跳、`SKIP LOCKED`、随机抖动、旧 Attempt 迟到结果保护、失败分类和人工重试；平台 Agent 每个逻辑步骤完成后立即持久化 Step 和 Checkpoint，Checkpoint 写入会再次校验当前 Attempt/租约，恢复读取按 Attempt 序号和步骤序号选择。
- 当前已增加受权限保护的活动运行取消命令：会清理租约、终止当前 Attempt、写入状态历史和操作账本；Worker 的迟到完成仍受 Attempt 条件保护。
- 缺口：Worker 强杀、心跳失败后的从 Checkpoint 恢复、暂停/恢复和跨实例压力测试仍需补齐。
- 验收：租约失效或 Worker 被杀后能从最后完整 Checkpoint 恢复；旧 Attempt 不能覆盖新 Attempt；终态操作幂等。

### P1-11 工具幂等并发与参数冲突

- 当前：逻辑幂等键为 `runId + logicalStepId + toolCode`，参数哈希独立保存并比较；数据库实现通过带 claim owner、lease、fencing token 的 `INSERT ... ON CONFLICT ... RETURNING` 原子抢占 RUNNING 记录，FAILED 或过期 RUNNING 只有在参数哈希一致时才可由下一次 Attempt 重新领取；参数冲突会被拒绝，未抢占的 Worker 不会重复调用外部工具。V37 回填 V35/V36 窗口内创建的租户授权，V38 将无法证明结果的历史 RUNNING 记录标记为 UNKNOWN。
- 缺口：仍需在真实 PostgreSQL 并发环境补充双 Worker 压力测试，以及 UNKNOWN 副作用结果的人工核验流。
- 验收：同一逻辑步骤参数变化必须拒绝；并发调用最多一个外部执行者；已完成结果可复用。

### P1-9 Agent 工具快照已落地，目录版本化仍需补齐

- 当前：AgentVersion 保存不可变工具代码集合；发布阶段会校验平台注册、租户授权、Schema 和执行模式；BPMN 节点只能进一步收紧；运行时执行集合为平台注册工具 ∩ 租户授权 ∩ AgentVersion 集合 ∩ 节点子集，未绑定工具会被拒绝。
- 缺口：工具 Schema/描述的目录版本尚未独立快照，MCP 连接器和工具目录兼容检查仍按 MCP 实施方案推进。
- 验收：发布版本引用不可变 ToolSet 快照，工具目录变更不能改变历史运行语义。

### P1-10 knowledge_search 暂停开放

- 当前：工具定义仍保留用于后续接入，但 V35 已关闭定义并删除现有租户授权；新租户触发器也不会自动授权。真实 Retriever、权限策略和应用服务完成前不得重新开启。
- 验收：数据库中工具为 disabled、租户授权为空；未配置检索能力时 Agent 不能看到或调用该工具。

### P1-4 结果策略与 Agent 运行规则仍需租户化

- 当前：已支持 Schema 校验、空结果、部分结果、业务拒绝、内容过滤和显式 `resultStatus`，未通过策略不会恢复 Flowable。
- 缺口：证据约束、Guardrail、租户业务规则、成本预算和人工确认尚未纳入版本化 Agent 契约。
- 验收：只有通过结构、业务、证据和 Guardrail 的结果才能推进流程，规则版本可追溯。

### P1-5 Provider 出站安全仍需纵深治理

- 当前：默认 HTTPS、超时、响应大小限制及常见 IPv4 内网地址拒绝已存在；开发环境可显式放行本地 HTTP。
- 缺口：IPv6 ULA、CGNAT、保留地址、云元数据地址、DNS Rebinding、私有 Provider 审批和统一出站网关仍需完善。
- 验收：未授权私网、异常端口、DNS 重绑定和元数据地址全部拒绝；授权私有 Provider 有明确审批和审计。

### P1-6 租户与 Provider 公平调度仍需跨实例配额

- 当前：Worker 已有有界线程池、队列和数据库层租户并发上限，默认每租户最多 2 个运行中任务。
- 缺口：Provider 配额、跨实例一致的租户/Provider 令牌、拒绝与积压指标、动态配额和优先级调度尚未完成。
- 验收：单一租户或慢 Provider 不能长期占满全部执行资源，跨实例压力测试下无无界线程、连接和重复领取。

### P1-7 核心服务与查询仓储仍偏大

- 当前：运维命令仓储已从 Worker 执行仓储拆出，参与人协调和完成事件已有独立边界。
- 缺口：`JdbcAgentRunExecutionRepository`、`AgentRunWorkerServiceImpl`、Flowable 查询/运行服务仍混合 SQL、映射和部分业务编排。
- 验收：查询、命令、映射、状态决策和持久化职责分离；领域规则不复制到 JDBC；核心状态机均有领域测试。

### P1-8 核心覆盖率和故障集成测试低于长期目标

- 当前：JaCoCo 已配置真实 `check`，当前阈值是保守基线；单元测试、架构测试和部分 PostgreSQL/Flowable 集成测试已存在。
- 缺口：核心模块逐步达到 60% 以上，Agent 完成事件、RLS、租约、重复消息、事务回滚和 Provider 兼容性还需更多真实环境测试。
- 验收：关键安全、租约、状态机、恢复和流程推进路径达到更高覆盖率，不能只用 Mock 证明闭环。

## 暂不列入问题的已完成项

- Provider 能力契约、脱敏错误摘要、恢复决策账本、人工重试操作账本已落地。
- Agent 工具注册、租户授权、输入 Schema、幂等键、执行审计和只读业务工具已落地。
- Cookie 认证的 CSRF 豁免现在只对已认证的 SERVICE principal 生效；普通 Cookie 用户即使携带任意服务令牌头也仍需 CSRF Token。
- Redis 流程锁已使用可配置的多续租线程、续租失效标记和事务提交前所有权校验；驳回未指定目标时不再猜测第一个 UserTask，而是要求调用方显式指定目标。
- Agent Definition 发布依赖 `AgentToolCatalogPort`，不再直接依赖 Runtime Registry/Policy Repository 的实现；工具真实性校验仍集中在应用端口背后的目录实现。
- OpenAPI lint、路由覆盖、兼容性检查、Checkstyle、SpotBugs、JaCoCo 和 ArchUnit 已接入质量门禁。
- Token Cookie、CSRF、RLS 基础策略、Attempt 复合约束和完成事件幂等链路已完成首版。
