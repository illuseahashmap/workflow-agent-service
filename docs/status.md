# 下一步计划与当前状态

更新时间：2026-08-25

本文是“下一步计划”唯一入口。长期原则见[长期设计总览](architecture/long-term-design.md)，具体缺陷见[待修复问题](quality/known-issues.md)。

## 一、当前基线

### 平台与工作流

- 已完成模块化单体拆分：`auth-engine`、`workflow-engine`、`rules-engine`、`agent-engine`、`knowledge-engine`、`platform-migrations`、`workflow-boot`。
- 已具备注册登录、HttpOnly Cookie、CSRF、租户/角色/权限、流程版本、派单规则版本和基础审计。
- 已具备流程启动、审批、驳回、转办、终止、实例查询、参与人解析和流程图展示。
- 已建立 PostgreSQL RLS、租户上下文、Redis 锁和可靠兜底命令。

### Agent 首个纵向闭环

- 已完成 AgentDefinition/AgentVersion、Provider、AgentRun/Attempt/Step/Checkpoint 和模型调用审计；平台 Agent Worker 在每个逻辑步骤完成后持久化幂等 Step/Checkpoint，传统执行器保留完成后兼容写入。
- 已完成正式 AgentRun 执行链路：Provider 调用、Schema 校验、基础结果策略、重试、租约心跳、Recovery、Outbox/Inbox 和 Flowable 恢复；平台 Agent 的逻辑步骤 Checkpoint 现在包含恢复游标和受限上下文，并在新 Attempt 读取。
- 已增加 `PLATFORM_AGENT` 执行模式的首个切片：计划阶段与执行阶段形成受控循环，仍复用同一 AgentRun 和流程恢复链路；当前仅允许后端显式注册的工具。
- 已增加显式 `AgentTool`/`AgentToolRegistry` 应用端口，结构化 `TOOL_CALL` 只能调用后端注册工具，未注册工具会拒绝；AgentVersion 已冻结工具代码集合，BPMN 节点只能继续收紧，当前尚未开放通用 HTTP、脚本或租户自定义代码工具。
- Provider 请求现在携带当前租户已授权工具的名称、描述和输入 Schema；Chat Completions 使用结构化工具调用，DeepSeek Ark Responses 端点暂按其兼容性使用受控文本工具协议，避免发送该端点不接受的 `tools` 参数。模型不能凭 Provider 凭证获得工具权限，工具仍由 Runtime 注册表和租户授权共同决定。后续应以 Provider 能力协商替代按端点判断。
- 已落地首个只读业务工具 `agent_run_status`，工具定义、租户授权、输入 Schema、稳定幂等键、带租约的数据库原子抢占和执行审计由 PostgreSQL 管理；工具输出不直接修改流程或 AgentRun。
- 已落地真实业务只读工具 `workflow_process_context`：Agent 可按租户读取流程实例元数据、当前人工任务和脱敏业务变量，继续复用 AgentRun/Step/审计链路；工具不能推进或修改流程。知识检索另有独立 `knowledge_search` 端口和授权边界。
- 已建立 `knowledge-engine` 的 Evidence 多态契约、授权范围求交、检索 Trace、知识源/文档/索引/摄取任务生命周期模型和 `knowledge_search` 只读工具端口；由于真实 Retriever、权限策略和应用服务尚未装配，V35 已将该工具默认禁用且撤销租户授权，真实 pgvector 检索和摄取 Worker 仍未接入。
- 已开放 `PLATFORM_AGENT` 前端配置；流程运行时自动注入受控 `processInstanceId`，模型无需把流程 ID作为业务输入，手动测试仍支持显式传入。
- 重试已使用可注入的指数退避 + 有界随机抖动，最终 `available_at` 在同一事务中持久化。
- 已增加类型化失败模型与恢复决策账本：Provider 临时/永久故障、输出/输入契约、工具协议、结果策略、配置、业务拒绝、截止时间和未分类异常均在边界处明确归类，再由恢复策略选择重试、修复、人工介入或终止；运行详情可查询安全诊断信息、Trace ID、Attempt、Step 和恢复决策。
- 已完成首节点及审批后 Agent 输入契约：后端按真实路径生成字段，前端动态渲染，命令边界再次校验必填输入。
- 已提供租户安全的流程实例 AgentRun 关联查询：`GET /agent-runs/process-instances/{processInstanceId}`，外部系统可以跟踪执行状态而不接触内部状态机。
- 已提供受权限保护的失败运行处置命令：修复 Provider、凭证或 Agent 配置后，可通过 `POST /agent-runs/{runId}/retry` 显式开启 30～3600 秒的新执行窗口并创建新的 Attempt；流程仍停留在原 Agent 节点，只有新 Attempt 成功后才恢复 Flowable，不能无限延长原始运行。
- 输入映射首版只允许标量、对象字段和整个数组；输出映射支持数组索引和通配投影。

### 工程质量基线

- 已接入 Maven Enforcer、Checkstyle、SpotBugs、JaCoCo、ArchUnit、OpenAPI lint、路由覆盖和兼容性检查。
- 前端已接入格式、Lint、类型检查、单元测试、构建和 Playwright E2E。

## 二、当前阶段与验收

| 优先级 | 工作项 | 当前状态 | 下一步验收 |
| --- | --- | --- | --- |
| P0 | 容器化集成测试 | CI 可执行，本机可能因 Docker 不可用而跳过 | PostgreSQL、Redis、Flowable、Flyway、RLS 和 HTTP 安全集成测试稳定通过 |
| P0 | 生产可观测性 | Trace、基础日志、健康检查和部分指标已有 | 指标、告警、审计查询和失败命令运维入口完整 |
| P0 | 流程操作审计 | 基础审计已有 | 发起、领取、审批、驳回、转办、终止、规则命中可追溯 |
| P1 | 租户纵深隔离 | 平台业务表已启用强制 RLS | 完成 Flowable 内部表评估和跨租户负面测试 |
| P1 | API 契约治理 | 路由覆盖、成功响应模型和 Agent 运行接口错误响应已继续收敛 | 全部认证接口错误响应、DTO、分页模型和客户端类型生成完整 |
| P1 | Agent Runtime 生产可靠性 | MODEL_ONLY 与 PLATFORM_AGENT 两阶段切片、失败分类、抖动重试和结果/子步骤 Checkpoint 已有 | 基于 Checkpoint 的恢复、取消/暂停/恢复、出站策略、跨实例公平调度、Guardrail 和故障测试完成 |
| P1 | Agent 交互扩展 | 输入契约和流程实例运行查询已完成首个切片 | 版本化表单、复杂对象/数组控件、外部幂等提交和待补录任务完成 |
| P1 | 受治理 RAG 最小闭环 | Evidence 多态契约、授权求交、生命周期模型、Trace 和 `knowledge_search` 工具边界已落地 | 完成真实摄取、pgvector/混合检索、Grounding、评测和多 Retriever 兼容验收 |
| P1 | 受治理 MCP 最小闭环 | Provider Tool Calling、Tool Registry、租户授权、Schema、幂等和审计已有；尚无 MCP 协议接入 | 按[MCP 短期实施方案](architecture/mcp-short-term-implementation-plan.md)完成连接器、目录快照、AgentVersion 工具绑定、只读 `tools/call`、安全与故障恢复验收 |

## 三、下一阶段顺序

1. 先完成 P0 生产基线：集成测试、可观测性和流程审计。
2. 再完成 P1 安全与契约：RLS 深度验证、API 响应模型和核心覆盖率。
3. 再完善 Agent Runtime：结果策略、Checkpoint 恢复、出站安全、公平调度、取消和人工确认。
4. 在不打断 P0/P1 加固的前提下，按 RAG-1 至 RAG-4 建立受治理知识检索闭环，为运行证据和后续渐进式自治提供可靠输入。
5. 并行完成 MCP-1 至 MCP-3 的只读闭环：连接器与目录版本、AgentVersion 精确绑定、`tools/call`、出站安全和故障测试；人工确认与未知结果处置完成后再实施 MCP-4 写工具。
6. 最后扩展组织、任务中心、表单、通知、委托、SLA、完整工具治理和证据驱动自治。

## 四、明确暂不做

- Dify 类通用 Agent 内部流程画布。
- 租户上传任意 Java、脚本、插件或任意目标 HTTP 工具。
- 租户配置任意本地 MCP `stdio` 命令，以及未经审核即动态使用 MCP 工具。
- 无边界的多 Agent 自主协作、复杂记忆和开放式工具生态。
- 在组织、表单和任务中心基础能力之前扩展高级企业功能。

## 五、完成判定

首个纵向闭环不等于 Agent MVP 完成。只有执行、恢复、幂等、权限、审计、取消、人工交互和 Flowable 集成全部通过容器化集成测试，才可将 Agent MVP 标记为完成。

最近一次验证：`agent_process` 第 3 版已验证“发起流程 → 生成输入契约 → 填写业务字段 → Agent 执行 → 后续节点继续运行”。
### Agent Runtime 近期加固

- Provider 适配器通过能力契约声明协议和原生 Tool Calling 能力，运行时不再直接根据凭证推断能力；Responses 兼容端点继续使用受控文本工具协议。
- Provider HTTP 错误只提取有长度限制且脱敏的诊断摘要，完整原始响应不落库；恢复决策账本会向授权运维界面提供可操作上下文。
- Agent Worker 领取查询支持租户级并发上限，避免单一租户占满所有执行槽位；上限通过 `WORKFLOW_AGENT_WORKER_MAX_RUNNING_PER_TENANT` 配置。
- 人工重试除状态历史外，写入独立的 `agent_run_operation` 操作账本；V31 同时为 Agent 操作和已存在的 Flowable 租户表补充纵深隔离策略。
- 结果策略支持显式 `resultStatus` 业务结果封套，只有 `SUCCESS` 才允许恢复流程；空结果、部分结果和业务拒绝均保持终止语义。
