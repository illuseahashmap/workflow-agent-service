# Agent MVP 实施设计

更新时间：2026-08-07

状态：实施前设计，用于把《Agent 协作节点架构设计》收敛为第一轮可开发任务。

## 1. 目标

第一轮只完成一个可靠的自主 Agent 节点闭环：

```text
已发布 AgentVersion
→ Flowable 异步可触发 Service Task
→ workflow-engine 创建 AgentRun + Outbox
→ agent-engine Worker 领取运行
→ Provider 执行或 Mock 执行
→ 保存 Attempt / Step / Checkpoint / ModelInvocation
→ 发布 Completed 或 Failed
→ workflow-engine 校验并恢复 Flowable
```

该版本的核心验收不是“回答多聪明”，而是证明 Agent 执行不会破坏流程事务、租户隔离、审计和恢复能力。

## 2. 范围

### 2.1 MVP 必做

1. `AgentDefinition`、不可变 `AgentVersion`、发布和禁用。
2. `AgentRun`、`AgentRunAttempt`、`AgentRunStep`、`AgentCheckpoint` 状态账本。
3. `AgentRunStateMachine` 统一执行状态转换。
4. PostgreSQL `platform_outbox_event` / `platform_inbox_event` 可靠事件。
5. Worker 租约、超时、重试、截止时间和租户/Provider 基础并发限制。
6. `OPENAI_COMPATIBLE` Provider 端口和 `MOCK` Provider 实现。
7. 最小 `knowledge.search` 只读工具接口，可先使用空实现或固定测试数据。
8. 基础 Guardrail：输入大小、输出 JSON Schema、流程变量名和敏感字段保护。
9. Flowable Agent Service Task 绑定、部署校验、完成/失败事件消费和流程恢复。
10. 管理 API、运行查询 API、错误码、Trace ID 和关键测试。

### 2.2 MVP 不做

1. 人工任务 Copilot 多轮对话和 SSE。
2. 高风险写工具审批闭环。
3. 完整知识库产品、文档上传、复杂切分、Rerank 和专用向量库。
4. 多 Agent、子 Agent、handoff、并行 Step 和通用 Agent 画布。
5. OAuth 用户委托、远程 Agent 协议和插件市场。
6. Provider Prompt/KV Cache、Embedding Cache 和 Retrieval Cache 的真实优化。
7. 生产级评测平台和自动反馈学习。

## 3. 模块边界

### 3.1 `agent-engine`

负责 Agent 管理、运行状态、Provider、检索、Guardrail、Worker 和审计账本。不依赖 `workflow-engine`、Flowable、Servlet 或具体 Web Controller。

建议包结构：

```text
io.github.illuseahashmap.agent
├── definition
│   ├── interfaces.rest
│   ├── application
│   ├── application.dto
│   ├── domain
│   └── infrastructure.persistence
├── runtime
│   ├── interfaces.rest
│   ├── application
│   ├── application.dto
│   ├── application.port
│   ├── domain
│   └── infrastructure.persistence
├── provider
│   ├── application.port
│   ├── domain
│   └── infrastructure.openai
├── retrieval
│   ├── application.port
│   ├── domain
│   └── infrastructure.persistence
├── guardrail
│   ├── application
│   └── domain
└── event
    ├── application
    ├── application.dto
    └── infrastructure.persistence
```

### 3.2 `workflow-engine`

负责 Flowable 侧 Agent 节点绑定、输入输出映射、部署校验、创建运行请求和消费运行结果。它不依赖模型 SDK，也不直接执行 Provider。

建议新增包：

```text
io.github.illuseahashmap.workflow.process.application.port
├── AgentRunGateway
└── AgentBindingRegistry

io.github.illuseahashmap.workflow.process.infrastructure.agent
├── AgentTaskDelegate
├── AgentBindingParser
├── AgentRunCompletedEventConsumer
├── AgentRunFailedEventConsumer
└── AgentProcessResumeService
```

`workflow-engine` 通过端口或事件表与 `agent-engine` 交互，不访问 `agent-engine.infrastructure`。

### 3.3 `shared-kernel`

只放跨上下文稳定契约：

```text
io.github.illuseahashmap.workflow.shared.event
├── IntegrationEventEnvelope
├── EventStatus
└── TraceContext
```

不要把 Agent 领域对象放进 `shared-kernel`。

### 3.4 `workflow-boot`

负责装配：

1. Outbox Dispatcher 定时任务。
2. Agent Worker 定时领取任务。
3. 事件消费者注册。
4. Trace ID、健康检查和运行配置。

## 4. 领域模型

### 4.1 Agent 定义

```text
AgentDefinition
├── id
├── tenantCode
├── code
├── name
├── description
├── status
└── createdBy

AgentVersion
├── id
├── tenantCode
├── definitionId
├── version
├── status
├── modelRef
├── systemPrompt
├── inputSchemaJson
├── inputMappingJson
├── outputSchemaJson
├── outputMappingJson
├── toolPolicyJson
├── budgetPolicyJson
├── retryPolicyJson
├── failurePolicyJson
├── guardrailPolicyJson
├── groundingPolicyJson
├── retrievalProfileRef
├── configFingerprint
├── publishedBy
└── publishedAt
```

`AgentVersion` 发布后不可修改。任何 Prompt、Schema、模型、工具、检索和失败策略变化都创建新版本。

### 4.2 Agent 运行

```text
AgentRun
├── AgentRunAttempt
│   ├── AgentRunStep(MODEL)
│   ├── AgentRunStep(RETRIEVAL)
│   ├── AgentRunStep(VALIDATION)
│   └── AgentCheckpoint
└── AgentResultEnvelope
```

`AgentRun` 表示业务运行，`AgentRunAttempt` 表示一次尝试，`AgentRunStep` 表示可审计步骤，`AgentCheckpoint` 表示恢复点。四者不能互相替代。

### 4.3 状态枚举

MVP 只实现：

```text
AgentRunStatus: QUEUED / RUNNING / SUCCEEDED / FAILED / TIMED_OUT / CANCELLED
AttemptStatus: QUEUED / RUNNING / SUCCEEDED / FAILED / TIMED_OUT / CANCELLED
StepStatus: PENDING / RUNNING / SUCCEEDED / FAILED / SKIPPED
ResultStatus: SUCCESS / EMPTY / PARTIAL / REJECTED / FAILED
```

`WAITING_HUMAN`、`WAITING_TOOL_APPROVAL` 和 `CANCEL_REQUESTED` 保留，不进入第一轮状态机。

## 5. 核心接口

### 5.1 Agent 管理应用服务

```java
public interface AgentDefinitionService {
    AgentDefinitionView create(AgentDefinitionCommand command);

    PageResult<AgentDefinitionView> page(Integer pageNum, Integer pageSize, String keyword);

    AgentVersionView createVersion(long definitionId, AgentVersionCommand command);

    AgentVersionView publish(long versionId);

    void disableVersion(long versionId);

    AgentTestRunResult test(long versionId, AgentTestRunCommand command);
}
```

### 5.2 Agent 运行应用服务

```java
public interface AgentRunService {
    AgentRunView get(long runId);

    void cancel(long runId);

    AgentRunView retry(long runId);

    List<AgentCheckpointView> checkpoints(long runId);
}
```

### 5.3 Workflow 调用端口

```java
public interface AgentRunGateway {
    AgentRunCreated createRun(AgentRunCreateCommand command);

    AgentRunSnapshot findRun(String tenantCode, long agentRunId);
}
```

`workflow-engine` 创建运行时只传显式上下文，不让 `agent-engine` 自行读取 Flowable。

### 5.4 Provider 端口

```java
public interface AgentProvider {
    AgentProviderResult execute(AgentProviderRequest request);

    AgentProviderCapabilities capabilities(ProviderRef providerRef);

    void cancel(AgentProviderCancellation cancellation);
}
```

MVP 至少提供：

1. `MockAgentProvider`，用于端到端测试。
2. `OpenAiCompatibleAgentProvider`，用于真实模型调用。

### 5.5 检索端口

```java
public interface KnowledgeRetriever {
    AgentResultEnvelope<RetrievalResult> search(RetrievalQuery query);
}
```

MVP 可以先使用 PostgreSQL 全文检索、固定样例数据或空实现，但必须写 `RetrievalTrace` 和 `Citation` 结构。

## 6. 状态机

状态转换只能通过 `AgentRunStateMachine`：

| 当前状态 | 命令 | 目标状态 | 约束 |
| --- | --- | --- | --- |
| `QUEUED` | `startLease` | `RUNNING` | Worker 持有有效租约 |
| `RUNNING` | `markSucceeded` | `SUCCEEDED` | 必需 Step 成功，结果策略允许继续 |
| `RUNNING` | `markFailed` | `FAILED` | 不可重试失败或重试耗尽 |
| `RUNNING` | `markTimedOut` | `TIMED_OUT` | 超过 `deadlineAt` 或 Provider 超时 |
| `QUEUED` | `cancel` | `CANCELLED` | 尚未执行时取消 |
| `RUNNING` | `cancel` | `CANCELLED` | Worker 检查取消标记后停止 |
| `RUNNING` | `retry` | `QUEUED` | 可重试错误且未超过策略 |
| 终态 | `complete/fail/cancel` | 原状态 | 幂等返回，不推进流程 |

每次状态变化写入状态历史，至少包含：

```text
tenantCode
agentRunId
attemptId
oldStatus
newStatus
reasonCode
operatorType
operatorId
traceId
createdAt
```

## 7. 数据库设计

第一轮使用 PostgreSQL。所有 Agent 表必须包含 `tenant_code`，禁止跨租户无条件查询。

### 7.1 必建表

```text
agent_definition
agent_definition_version
agent_provider
agent_credential
tenant_agent_runtime_policy
agent_run
agent_run_attempt
agent_run_step
agent_run_checkpoint
agent_run_state_history
agent_model_invocation
agent_retrieval_trace
agent_guardrail_evaluation
platform_outbox_event
platform_inbox_event
workflow_agent_binding
```

`agent_tool_invocation` 可以在第一轮建表但不接高风险工具闭环。`agent_knowledge_source`、`agent_knowledge_document`、`agent_knowledge_chunk` 可以只建最小字段，支持测试检索。

### 7.2 关键唯一约束

```text
unique(tenant_code, agent_definition.code)
unique(tenant_code, definition_id, version)
unique(tenant_code, idempotency_key) on agent_run
unique(tenant_code, process_instance_id, execution_id, activity_activation_id, agent_version_id)
unique(tenant_code, agent_run_id, attempt_no)
unique(tenant_code, agent_run_id, attempt_id, sequence_no) on agent_run_checkpoint
unique(event_id) on platform_inbox_event
unique(event_id) on platform_outbox_event
```

### 7.3 Flyway 顺序

1. Agent 定义、Provider、凭据和 runtime policy。
2. AgentRun、Attempt、Step、Checkpoint、状态历史和模型调用。
3. Outbox/Inbox 事件表。
4. workflow-agent 绑定投影。
5. 检索、Guardrail 和可选工具调用账本。

迁移文件只能追加，不修改已发布版本。

## 8. 事件协议

### 8.1 统一信封

```json
{
  "eventId": "uuid",
  "eventType": "AgentRunRequested.v1",
  "eventVersion": 1,
  "tenantCode": "default",
  "aggregateId": "10001",
  "traceId": "trace-id",
  "occurredAt": "2026-08-07T10:00:00Z",
  "payload": {}
}
```

### 8.2 MVP 事件

```text
AgentRunRequested.v1
AgentRunCancellationRequested.v1
AgentRunCompleted.v1
AgentRunFailed.v1
```

### 8.3 Payload 字段

`AgentRunRequested.v1`：

```text
agentRunId
attemptId
agentVersionId
processDefinitionId
processInstanceId
executionId
taskId
activityId
activityActivationId
idempotencyKey
deadlineAt
```

`AgentRunCompleted.v1`：

```text
agentRunId
attemptId
agentVersionId
processInstanceId
executionId
activityId
activityActivationId
resultStatus
outputSnapshot
traceId
```

`AgentRunFailed.v1`：

```text
agentRunId
attemptId
processInstanceId
executionId
activityId
activityActivationId
errorCode
errorMessage
retryable
traceId
```

消费者必须先写 Inbox 去重，再执行业务动作。重复事件对终态返回成功，不重复恢复 Flowable。

## 9. Flowable 集成

### 9.1 BPMN 绑定

MVP 只支持自主 Agent Service Task：

```xml
<serviceTask id="riskAnalysis"
             flowable:delegateExpression="${agentTaskDelegate}"
             flowable:async="true"
             flowable:triggerable="true">
    <extensionElements>
        <agent:binding versionId="agent-version-1"
                       mode="AUTONOMOUS"
                       inputMapping="risk-analysis-input-v1"
                       outputMapping="risk-analysis-output-v1"
                       timeout="PT2M"
                       failurePolicy="BPMN_ROUTE" />
    </extensionElements>
</serviceTask>
```

部署校验失败时拒绝部署：

1. AgentVersion 不存在、未发布或租户不匹配。
2. 节点不是支持的 Service Task。
3. `flowable:async` 或 `flowable:triggerable` 缺失。
4. 输入映射和输出映射语法非法。
5. 输出变量名不符合平台变量白名单。
6. 超时和预算超出租户策略。

### 9.2 运行时流程

```text
AgentTaskDelegate
→ 解析绑定
→ 构造 inputSnapshot
→ 创建 AgentRun(QUEUED)
→ 写 AgentRunRequested Outbox
→ Flowable 等待触发
```

完成事件消费：

```text
读取 Inbox 去重
→ 校验 AgentRun 终态和 attemptId
→ 获取流程实例锁
→ 查询当前执行仍在 activityId
→ 校验 activityActivationId
→ 校验输出变量
→ setVariables
→ RuntimeService.trigger
→ 写消费成功
```

失败事件消费：

```text
读取 Inbox 去重
→ 校验流程仍等待该节点
→ 写失败变量或失败原因
→ 按 failurePolicy 触发 BPMN 失败路由或保留待运维处理
```

## 10. API 设计

### 10.1 管理 API

```text
POST /agent/definitions
GET  /agent/definitions
GET  /agent/definitions/{id}
POST /agent/definitions/{id}/versions
GET  /agent/definitions/{id}/versions
POST /agent/versions/{id}/publish
POST /agent/versions/{id}/disable
POST /agent/versions/{id}/test
```

### 10.2 运行 API

```text
GET  /agent/runs
GET  /agent/runs/{runId}
POST /agent/runs/{runId}/cancel
POST /agent/runs/{runId}/retry
GET  /agent/runs/{runId}/attempts
GET  /agent/runs/{runId}/steps
GET  /agent/runs/{runId}/checkpoints
```

### 10.3 DTO 原则

1. 请求体不接受 `tenantCode`，统一使用当前主体上下文。
2. 响应不返回明文凭据、完整敏感 Prompt、隐藏思维链和 Provider 原始密钥错误。
3. 错误响应统一包含 `code`、`message`、`traceId`。
4. 分页使用项目现有 `PageResult`。

## 11. Worker 设计

### 11.1 领取规则

Worker 只领取：

```text
status = QUEUED
available_at <= now()
deadline_at > now()
lease_expires_at is null or lease_expires_at < now()
```

排序：

```text
priority DESC, available_at ASC, id ASC
```

领取时必须在同一事务中设置：

```text
status = RUNNING
lease_owner
lease_expires_at
started_at
current_attempt_id
```

### 11.2 执行步骤

MVP 固定步骤：

```text
VALIDATION_INPUT
MODEL_CALL
VALIDATION_OUTPUT
PUBLISH_RESULT
```

如果启用 `knowledge.search`，在 `MODEL_CALL` 前增加：

```text
RETRIEVAL_KNOWLEDGE
```

每个步骤必须写 `AgentRunStep`。模型调用必须写 `agent_model_invocation`。恢复点必须写 `AgentCheckpoint`。

### 11.3 超时和取消

1. Worker 每个步骤前检查 `deadlineAt`。
2. Provider 总超时不能超过剩余 deadline。
3. `cancel` 对运行中任务只标记取消请求或直接进入 `CANCELLED`，MVP 不实现复杂人工等待。
4. Worker 重启后从最后完整 checkpoint 恢复。

## 12. Provider MVP

### 12.1 Mock Provider

用于测试，输入固定返回 JSON：

```json
{
  "decision": "APPROVE",
  "summary": "Mock result",
  "confidence": 0.9
}
```

Mock Provider 必须支持：

1. 成功。
2. 空响应。
3. 非 JSON 响应。
4. 超时。
5. 可重试异常。
6. 不可重试异常。

### 12.2 OpenAI Compatible Provider

最小配置：

```text
baseUrl
model
credentialId
temperature
maxOutputTokens
connectTimeout
readTimeout
```

MVP 不保存完整原始响应正文到普通审计。模型调用记录保存：

```text
actualModel
providerRequestId
inputTokens
outputTokens
reasoningTokens
estimatedCost
firstTokenLatencyMs
totalLatencyMs
status
```

## 13. Guardrail MVP

第一轮 Guardrail 只做确定性校验：

1. 输入 JSON 大小限制。
2. 禁止敏感字段进入 Prompt 快照。
3. 输出必须满足 `outputSchemaJson`。
4. 输出变量名必须通过白名单。
5. 输出变量值大小必须小于流程变量限制。
6. `knowledge.search` 结果不足时按 `failurePolicy` 进入 `EMPTY` 或 `HUMAN_REVIEW` 预留语义。

复杂内容安全、PII 检测、Prompt Injection 分类和外部审核器后续实现。

## 14. 测试计划

### 14.1 单元测试

1. `AgentRunStateMachineTest`：全部合法和非法转换。
2. `AgentResultEnvelopeTest`：空、部分、拒绝和失败语义。
3. `AgentVersionPublishPolicyTest`：发布后不可修改。
4. `AgentWorkerLeasePolicyTest`：租约领取、过期和重复领取。
5. `AgentOutputMappingPolicyTest`：变量名、大小和 Schema 校验。

### 14.2 Repository 测试

1. 租户过滤。
2. 唯一约束。
3. Outbox/Inbox 去重。
4. AgentRun 领取排序。
5. Attempt 和 Checkpoint 不覆盖历史。

### 14.3 集成测试

1. BPMN Agent 节点端到端成功恢复。
2. Provider 超时进入失败或重试。
3. 重复 `AgentRunCompleted` 不重复推进流程。
4. 迟到完成事件不修改已经离开节点的流程。
5. Worker 重启后从 checkpoint 恢复。
6. 跨租户不能读取 AgentVersion、AgentRun、Credential 和事件。
7. 输出 Schema 不匹配时流程不继续。
8. Outbox Dispatcher 失败后可重试。

## 15. 开发顺序

1. 新增 `shared-kernel` 事件信封和错误码。
2. 新增 `agent-engine` domain 枚举、实体 record、状态机和单元测试。
3. 新增 Flyway 迁移和 repository。
4. 新增 AgentDefinition / AgentVersion 管理 API。
5. 新增 AgentRun 创建、查询、Attempt、Step、Checkpoint。
6. 新增 Outbox/Inbox repository 和 Dispatcher。
7. 新增 Worker 领取、Mock Provider 和运行状态推进。
8. 新增 workflow-engine AgentTaskDelegate 和绑定解析。
9. 新增 Completed/Failed 消费者和 Flowable 恢复。
10. 新增 OpenAI Compatible Provider。
11. 新增最小 `knowledge.search`、RetrievalTrace 和 Citation。
12. 跑通端到端集成测试，再扩展 Guardrail 和前端。

每一步完成后必须保持 `mvn test` 或对应模块测试可通过。

## 16. 风险与裁剪

| 风险 | 裁剪策略 |
| --- | --- |
| 第一轮表太多 | 先保留 Step、Checkpoint、ModelInvocation、Outbox/Inbox，工具和知识表只建最小投影 |
| Provider 接入拖慢闭环 | 先用 Mock Provider 跑通 Flowable 恢复，再接 OpenAI Compatible |
| RAG 范围失控 | `knowledge.search` 只做只读工具和 Trace，不做文档平台 |
| Guardrail 范围失控 | 只做确定性 Schema、变量和大小校验 |
| Worker 恢复复杂 | 先支持步骤级 checkpoint 和幂等读工具，不支持写工具重放 |
| 多租户测试遗漏 | 所有 repository 方法强制 `tenantCode`，集成测试覆盖负例 |

## 17. MVP 完成判定

满足以下条件后，才进入 Copilot 和工具审批：

1. 一个 BPMN Agent Service Task 可以异步执行并恢复流程。
2. Worker 停止后可以从最后 checkpoint 恢复。
3. 重复完成、失败、取消和迟到事件不会重复推进流程。
4. 两个租户的数据、凭据、AgentVersion 和运行记录互相隔离。
5. AgentRun 可以还原版本、输入摘要、Attempt、Step、Checkpoint、模型调用和输出。
6. 输出不满足 Schema 时流程不继续。
7. Provider 超时、空响应、非 JSON 响应和可重试异常都有确定结果。
8. Outbox/Inbox、PostgreSQL、Redis、Flowable 和 Worker 关键链路有集成测试。

## 18. 与长期设计的关系

`agent-collaboration-design.md` 是长期架构基线，本文件是第一轮实现计划。后续新增 Copilot、人工审批、完整 RAG、远程 Agent、缓存优化和多 Agent 时，必须继续复用本文件确定的 `AgentRun`、`Attempt`、`Step`、`Checkpoint`、事件信封、状态机和审计模型，不得另起一套运行账本。
