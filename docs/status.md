# 项目状态总览

更新时间：2026-08-10

本文档是“现在做到什么程度、下一步做什么”的唯一入口。详细设计文档可以更长，但不得与本文档的状态结论冲突。

## 一、已完成基线

### 平台与工作流

- [x] `auth-engine`、`workflow-engine`、`rules-engine`、`agent-engine`、`platform-migrations` 和 `workflow-boot` 模块化拆分。
- [x] 用户注册、登录、HttpOnly Cookie、CSRF、租户切换、成员、角色和权限管理。
- [x] 流程定义部署、版本激活、查询、发布、删除和流程图展示。
- [x] 流程启动、审批、驳回、转办、终止、实例查询和任务参与人解析。
- [x] 派单规则版本、继承、处理人/候选人/会签人配置。
- [x] Redis 流程锁、可靠兜底命令和基础操作审计。

### Agent 基础设施

- [x] AgentDefinition 与不可变 AgentVersion 管理。
- [x] Provider 管理、加密凭据保存和 OpenAI Compatible Provider。
- [x] AgentRun、Attempt、Step、Checkpoint、State History 和 Model Invocation。
- [x] Outbox/Inbox、Worker 租约、`SKIP LOCKED` 领取和 Mock Provider。
- [x] 手动测试通过正式 AgentRun 链路执行，不存在 Controller 直连模型的旁路。
- [x] Provider 失败分类、重试、终态幂等和迟到事件保护的基础实现。

### 架构与工程治理

- [x] 运行时、参与人路径解析和任务操作支持职责拆分。
- [x] 关键派单规则不变量下沉到领域对象并有领域单元测试。
- [x] PostgreSQL 强制 RLS、连接级租户上下文、平台管理员/系统 Worker/认证引导上下文。
- [x] OpenAPI 全量公开 REST 路由覆盖、Redocly lint 和 PR 破坏性变更检查。
- [x] Maven Enforcer、Checkstyle、SpotBugs、JaCoCo `check`、ArchUnit 和 CI 报告归档。

## 二、当前进行中

| 优先级 | 工作项 | 当前状态 | 下一步验收 |
| --- | --- | --- | --- |
| P0 | 容器化集成测试闭环 | CI 可执行，本机缺少 Docker/Maven | CI 稳定通过 PostgreSQL、Redis、Flowable、Flyway 和 HTTP 安全集成测试 |
| P0 | 生产可观测性 | 基础日志和健康检查已有 | Trace ID、结构化日志、指标、告警和失败命令运维入口完整 |
| P0 | 流程操作审计 | 基础审计已有 | 发起、领取、审批、驳回、转办、终止、规则命中可完整追溯 |
| P1 | RLS 深度覆盖 | 平台业务表已启用 V16 | 完成 Flowable 内部表归属评估和跨租户负面测试 |
| P1 | API 契约细化 | 路由已全量覆盖，部分响应仍为通用 `ApiResponse` | 补充 DTO 响应模型、错误码和客户端类型生成 |
| P1 | 覆盖率提升 | 已有基础门禁 | 核心模块逐步提升至 60% 以上，并提高关键安全/并发用例覆盖 |

## 三、下一阶段目标

### 阶段 A：生产基线

1. 让 CI 的 Maven、Docker、Testcontainers 集成测试稳定且可重复。
2. 补齐 Trace、指标、告警、审计查询和失败命令恢复入口。
3. 完成 RLS 负面测试、备份恢复和数据归档方案。
4. 评估 OAuth 2.1/OIDC、会话撤销、刷新和密钥轮换。

### 阶段 B：工作流平台能力

1. 组织目录、候选组、任务中心、表单、通知、委托、超时和升级。
2. 建立流程定义、派单规则、表单版本的显式兼容关系。
3. 继续拆分大型查询服务和 JDBC 仓储，保持领域规则不回流到基础设施。

### 阶段 C：Agent MVP 闭环

1. BPMN Agent Service Task 异步调用并可靠恢复流程。
2. 完成取消、暂停、恢复、超时、人工确认和失败补偿。
3. 完成工具权限、人工审批、预算、成本、模型版本和全链路审计。
4. 完成 Provider 降级、限流、背压和生产观测。

## 四、明确暂不做

- Dify 类通用 Agent 内部流程画布。
- 租户上传任意 Java、脚本、插件或任意目标 HTTP 工具。
- 完整多 Agent 自主协作、复杂记忆和开放式工具生态。
- 候选组、表单设计器和 SLA 之前的高级企业能力扩展。

## 五、状态判断规则

“具备模型调用能力”不等于“Agent MVP 完成”。只有 Agent 执行、恢复、幂等、权限、审计、取消和 Flowable 交互全部通过集成测试，才可以将 Agent MVP 标记为完成。
