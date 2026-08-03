# Agent 协作节点架构设计

更新时间：2026-08-03
状态：已验证设计基线，尚未实施

## 1. 背景

`workflow-agent-service` 计划在现有多租户 Flowable 工作流平台中引入 Agent 能力，使 Agent 能作为受约束的流程参与者，与人工任务共同完成一个业务流程。

本方案不以复制 Dify 的完整 Agent 工作流画布为目标。平台中的两类编排职责必须保持分离：

- Flowable 负责 BPMN 业务流程、人工任务、长期状态、事务一致性和补偿路径。
- `agent-engine` 负责模型调用、对话、工具执行、结构化输出、运行状态和成本审计。

Agent 不直接控制 Flowable，也不绕过工作流应用服务完成任务。

## 2. 设计目标

1. 支持 Agent 自主节点、人工任务 Copilot 和 Agent 结果人工复核三种协作模式。
2. 支持租户自行配置模型 Provider、凭据、Agent 行为和可用工具。
3. Agent 配置采用草稿、测试、发布、不可变版本的生命周期。
4. Agent 长时间运行不得占用 Flowable 命令事务或 Web 请求线程。
5. 所有运行必须可恢复、可重试、可取消、可人工接管和可审计。
6. 模型、工具、凭据、数据和成本必须按租户隔离。
7. BPMN 流程实例必须绑定确定的 Agent 版本，保证历史运行可复现。

## 3. 非目标

第一阶段不实现以下能力：

- Dify 类通用 Agent 内部流程画布。
- 租户上传 Java、脚本、插件或其他可执行代码。
- 租户配置任意目标地址的 HTTP 工具。
- 跨流程实例的无边界长期记忆。
- Agent 自动获得流程审批、终止、转办等高风险权限。
- 多 Agent 自主协商和群体编排。
- 内置向量知识库和文档摄取平台。

这些能力需要在核心执行链路稳定后单独设计。

## 4. 核心原则

### 4.1 Flowable 是流程状态的唯一权威

流程实例、执行路径和人工任务状态只由 `workflow-engine` 通过 Flowable API 修改。`agent-engine` 只能提交运行结果事件，不能直接调用 `RuntimeService` 或 `TaskService`。

### 4.2 Agent 执行与流程事务隔离

模型和工具调用不得发生在 Flowable 数据库事务中。流程进入 Agent 节点时只持久化等待状态和运行请求，实际执行由事务外的 Agent Worker 完成。

### 4.3 Agent 输出是建议或结构化数据

Agent 输出必须通过 JSON Schema 校验和变量映射后才能进入流程上下文。涉及外部写操作、权限变更、付款、流程终止等高风险动作时，必须经过人工确认或确定性规则授权。

### 4.4 配置版本不可变

已发布的 Agent 版本不可修改。需要调整提示词、模型、工具或输出结构时必须发布新版本。已部署流程继续引用原版本，除非重新部署流程定义。

### 4.5 密钥只由服务端解析

BPMN XML、Agent 定义、接口响应、日志和审计记录只保存 `credentialId`，不得保存或返回明文密钥。

## 5. 协作模式

### 5.1 自主 Agent 节点

自主节点用于分类、摘要、信息提取、风险分析和报告生成等不需要实时人工输入的工作。

节点采用 Flowable 异步、可触发 Service Task：

```xml
<serviceTask id="riskAnalysis"
             name="Agent 风险分析"
             flowable:delegateExpression="${agentTaskDelegate}"
             flowable:async="true"
             flowable:triggerable="true">
    <extensionElements>
        <agent:binding versionId="agent-version-1024"
                       mode="AUTONOMOUS"
                       inputMapping="risk-analysis-input-v1"
                       outputMapping="risk-analysis-output-v1"
                       timeout="PT2M"
                       failurePolicy="BPMN_ROUTE" />
    </extensionElements>
</serviceTask>
```

`flowable:async="true"` 保证流程状态先持久化；`flowable:triggerable="true"` 使节点在发出运行请求后保持等待，直到工作流适配器收到完成事件并触发执行继续。该模式用于避免外部响应早于等待状态持久化的竞态。

执行过程：

```text
进入 Agent 节点
→ agentTaskDelegate 校验绑定并写入 Outbox
→ Flowable 持久化等待状态
→ agent-engine 创建 AgentRun
→ 调用模型和受控工具
→ 输出通过 JSON Schema 校验
→ 写入 AgentRunCompleted 事件
→ workflow-engine 获取流程实例锁并校验当前执行
→ 写入映射后的流程变量
→ 触发 Service Task 继续流转
```

失败状态通过受控结果变量进入显式 BPMN 网关或边界超时事件，不允许异常被静默吞掉。

### 5.2 人工任务 Copilot

Copilot 绑定在普通 User Task 上。流程停留在人工任务，Agent 提供分析、追问和表单建议，但没有完成任务的权限。

```xml
<userTask id="manualReview" name="人工复核" flowable:assignee="${reviewer}">
    <extensionElements>
        <agent:binding versionId="agent-version-2048"
                       mode="HUMAN_COPILOT"
                       memoryScope="TASK"
                       completionRequirement="OPTIONAL" />
    </extensionElements>
</userTask>
```

约束：

- 人工任务创建后通过 Outbox 异步启动 Agent，不在任务监听器中直接调用模型。
- 用户消息只能由当前任务的合法处理人发送。
- 最终批准、驳回、转办和提交仍使用人工用户身份。
- 任务完成后，未结束的 AgentRun 应进入取消流程；迟到结果只记录审计，不得修改已完成任务。
- `completionRequirement=REQUIRED` 时，后端在完成人工任务前校验指定 AgentRun 已成功，前端禁用按钮不能代替后端校验。

### 5.3 Agent 结果人工复核

高风险场景使用两个显式 BPMN 节点：

```text
自主 Agent 节点 → 人工复核 User Task → 通过 / 退回重做 / 人工处理
```

该模式不增加新的运行语义，复用自主节点和普通人工任务。是否需要人工复核由 BPMN 显式表达，不隐藏在 Agent 黑盒中。

## 6. 模块与依赖边界

目标模块关系：

```text
workflow-boot
├── auth-engine
├── workflow-engine
├── agent-engine
├── rules-engine
├── platform-migrations
└── shared-kernel

workflow-engine ──integration event──> agent-engine
agent-engine ──integration event──> workflow-engine
```

- `agent-engine` 依赖 `shared-kernel`，不依赖 `workflow-engine` 和 Flowable。
- `workflow-engine` 不依赖具体模型 SDK。
- `workflow-boot` 作为组合根装配两个上下文的事件消费者和基础设施配置。
- `shared-kernel` 只定义通用事件信封、租户上下文和主体信息，不承载 Agent 业务模型。
- 跨上下文事件通过 Outbox/Inbox 持久化传递，不使用仅存在于内存的 Spring Event 作为可靠消息机制。

第一阶段可使用 PostgreSQL Outbox Dispatcher；事件协议保持与传输方式无关，后续可以替换为 Kafka 或其他消息中间件。

## 7. Agent 领域模型

### 7.1 AgentDefinition

表示租户可管理的 Agent，包含名称、说明、状态和所有者，不直接保存可执行配置。

### 7.2 AgentVersion

表示不可变的已发布版本，至少包含：

- Provider 和模型引用
- 系统行为指令
- 模型参数
- 输入定义和输出 JSON Schema
- 工具白名单
- 记忆范围
- 超时、重试和预算策略
- 人工确认策略
- 配置内容指纹

状态为 `DRAFT`、`PUBLISHED` 或 `RETIRED`。只有 `PUBLISHED` 版本可以部署到 BPMN。

### 7.3 AgentRun

表示一次可恢复执行，状态为：

```text
QUEUED
RUNNING
WAITING_HUMAN
WAITING_TOOL_APPROVAL
SUCCEEDED
FAILED
TIMED_OUT
CANCEL_REQUESTED
CANCELLED
```

每次运行至少关联：

```text
tenantCode
agentVersionId
processDefinitionId
processInstanceId
executionId
taskId（可空）
activityId
activityActivationId
conversationId（可空）
idempotencyKey
attempt
```

`activityActivationId` 在节点每次进入时生成，避免循环流程再次进入同一节点时被错误判定为重复执行。

### 7.4 Conversation 与 Message

Conversation 只允许以下范围：

- `TASK`：当前人工任务。
- `PROCESS_INSTANCE`：当前流程实例。
- `NONE`：单次运行，不保留会话上下文。

第一阶段默认 `TASK`，禁止跨流程实例记忆。Message 保存用户可见内容、角色、顺序号、状态和必要的引用证据，不保存模型私有思维链。

### 7.5 AgentCheckpoint

Agent Worker 不得把完整模型与工具循环视为一次不可恢复的方法调用。每次模型响应、工具请求、工具结果和人工中断后都应保存检查点，至少包含：

- 当前步骤和单调递增序号
- Provider 会话或响应标识
- 下一步待执行动作
- 已完成工具调用引用
- 待审批工具调用引用
- 上下文消息窗口或其可重建引用
- Token、费用和迭代次数累计值

服务重启或 Worker 租约过期后，从最后一个完整检查点继续。检查点只保存恢复所需的规范化状态，不保存 JVM 对象、函数或模型私有思维链。

### 7.6 ToolDefinition 与 ToolInvocation

工具由平台管理员注册，租户管理员授权，Agent 发布者选择。工具定义包含输入输出 Schema、风险级别、超时、幂等能力和凭据策略。

风险级别：

- `READ_ONLY`：只读查询。
- `REVERSIBLE_WRITE`：可补偿写操作。
- `HIGH_RISK_WRITE`：高风险操作，必须人工确认。

工具执行器负责注入凭据和再次校验参数。模型永远不能读取工具凭据。

## 8. Provider 与凭据

### 8.1 Provider 类型

第一阶段只实现 `OPENAI_COMPATIBLE` Provider，以统一支持云端模型、Ollama、vLLM 和其他兼容服务。

后续可增加：

- 特定云厂商 Provider，用于支持其专有鉴权和能力。
- `REMOTE_AGENT`，用于调用独立部署的完整 Agent 服务。

所有 Provider 实现统一端口：

```java
public interface AgentProvider {
    AgentExecutionResult execute(AgentExecutionContext context);

    AgentExecutionResult resume(AgentResumeContext context);

    void cancel(AgentCancellationContext context);
}
```

第一阶段使用 LangChain4j 最新稳定 BOM 的低层 `ChatModel`、`ToolSpecification`、结构化输出和消息类型作为 Provider 适配器实现基础，不使用仍标记为实验性的 `langchain4j-agentic` 模块，也不让 LangChain4j 类型进入领域模型。

工具循环由平台状态机控制，而不是直接使用自动执行工具的黑盒高层 API。这样才能在工具执行前完成租户权限、风险审批、幂等和检查点持久化。`AgentProvider` 端口隔离 LangChain4j，后续可以替换为 Spring AI 或直接厂商 SDK。

### 8.2 凭据来源

支持两类凭据所有权：

- `PLATFORM_MANAGED`：平台提供模型账号，租户使用分配的模型和配额。
- `TENANT_MANAGED`：租户自带密钥，即 BYOK。

平台登录 Token、`X-Workflow-Token`、模型 API Key 和工具凭据彼此独立，禁止复用或透传。

### 8.3 密钥存储

- 数据库保存 AES-GCM 加密密文、密钥版本和非敏感元数据。
- 复用当前主密钥配置能力，但通过独立 `SecretCipher` 端口隔离实现。
- 接口只支持新增、轮换、验证和删除，不提供明文读取。
- 生产部署优先使用 Vault/KMS 管理主密钥。
- 删除或停用凭据前检查已发布 Agent 版本和运行任务引用。

## 9. 租户自主配置与治理

### 9.1 生命周期

```text
创建 Agent 草稿
→ 配置行为、模型、工具和 Schema
→ 沙箱测试
→ 发布权限校验
→ 发布不可变版本
→ 工作流设计者绑定版本
→ 部署时再次校验
```

工作流节点只允许配置：

- 已发布 Agent 版本
- 协作模式
- 输入输出映射
- 本节点附加说明
- Agent 工具白名单的子集
- 超时、失败路由和人工确认要求

节点配置只能收紧 AgentVersion 的权限，不能扩大 Provider、工具、数据和预算边界。

### 9.2 权限

新增细粒度权限：

| 权限 | 用途 |
| --- | --- |
| `agent:provider:manage` | 管理租户 Provider 和凭据 |
| `agent:definition:read` | 查看 Agent 和已发布版本 |
| `agent:definition:write` | 编辑草稿和执行沙箱测试 |
| `agent:definition:publish` | 发布或停用 Agent 版本 |
| `agent:tool:manage` | 管理租户可用工具 |
| `agent:run:read` | 查看业务范围内的运行与会话 |
| `agent:run:operate` | 取消、重试和人工接管 |
| `agent:audit:read` | 查看完整审计、Token 和费用 |

默认由 `TENANT_ADMIN` 管理 Provider 和租户授权。Agent 编辑、发布和工作流部署应支持权限分离，避免单个普通用户同时控制凭据、Agent 行为和流程发布。

## 10. 运行时交互

### 10.1 人机对话

运行页面通过 REST 提交命令，通过 SSE 接收状态和消息更新：

```text
POST 用户消息或操作命令
→ 服务端鉴权、幂等校验并持久化
→ Agent Worker 异步处理
→ SSE 推送状态和完成消息
→ 断线后使用事件序号恢复，必要时回退为 REST 轮询
```

SSE 仅作为实时传输，不作为状态存储。最终消息、运行状态和事件序号必须持久化，客户端刷新或重连后可以恢复。

人工任务界面提供：

- 对话与追问
- Agent 当前状态
- 结论、依据和结构化结果
- 工具调用及人工确认
- 采用建议、修改后采用、重新生成和转人工处理

### 10.2 工具执行身份

工具执行必须声明身份策略：

- `PROCESS_INITIATOR`：代表流程发起人，仅访问其有权访问的数据。
- `TASK_OPERATOR`：代表当前任务处理人，仅用于 Copilot。
- `TENANT_SERVICE_ACCOUNT`：用于可审计的租户后台任务。

异步运行不得长期保存浏览器访问 Token。第一阶段优先使用租户服务账号；用户委托能力后续通过短期 Token Exchange 或 OAuth 授权实现。

## 11. 可靠性设计

### 11.1 Outbox/Inbox

流程命令、运行请求和状态变更与各自业务数据在同一数据库事务中写入 Outbox。消费者使用 Inbox 去重，事件至少投递一次，业务处理保证幂等。

核心事件：

```text
AgentRunRequested.v1
AgentRunCancellationRequested.v1
AgentRunCompleted.v1
AgentRunFailed.v1
AgentHumanInputRequired.v1
```

事件信封必须包含 `eventId`、`eventType`、`eventVersion`、`tenantCode`、`occurredAt`、`traceId` 和业务载荷。

### 11.2 幂等与并发

- AgentRun 以 `idempotencyKey` 建立唯一约束。
- Worker 通过带过期时间的租约领取 AgentRun，并定期续约；租约过期后其他 Worker 可以从检查点接管。
- 同一 Conversation 同时只允许一个会改变会话状态的 AgentRun，避免消息顺序分叉。
- 回调先检查运行状态和 Flowable 当前 Activity，再修改流程。
- 恢复流程时使用现有流程实例锁，但必须在独立事务中获取，不得重入原流程事务。
- 重复完成、迟到完成和取消后的完成事件只记录审计，不重复触发流程。
- 人工任务完成与 Agent 返回并发时，以已提交的 Flowable 任务状态为准。

### 11.3 失败处理

- Provider 调用设置连接、首包和总执行超时。
- 重试只适用于明确可重试错误，并使用指数退避和随机抖动。
- 工具写操作必须提供业务幂等键；不具备幂等能力时禁止自动重试。
- 每次运行设置最大模型轮次、最大工具调用次数和最大累计 Token，防止无限循环。
- 超过重试次数进入失败状态，由 BPMN 失败路由或人工接管处理。
- 服务重启后从持久化状态恢复未完成运行。

## 12. 安全与合规

1. 所有 Agent 数据和查询必须带 `tenantCode`，并建立租户隔离集成测试。
2. Provider 密钥、工具凭据和用户 Token 不进入 Prompt、日志、消息或审计明文。
3. 工具参数由后端根据 JSON Schema 和业务权限再次校验，不能信任模型输出。
4. HTTP 工具需要目标地址白名单、DNS/IP 校验、响应大小限制和超时，防止 SSRF。
5. Prompt、知识内容和外部工具结果均视为不可信输入，不能覆盖平台安全策略。
6. 高风险工具调用必须产生待审批记录，由具备权限的人工确认。
7. 输入模型前按数据分类执行字段过滤和脱敏。
8. 审计保存简明理由、引用证据、工具参数摘要和结果，不保存私有思维链。
9. 每个租户设置模型、Token、并发、运行时长和费用配额。

## 13. 数据表建议

业务表统一由 `platform-migrations` 管理：

```text
agent_provider
agent_credential
agent_definition
agent_definition_version
agent_tool_definition
agent_version_tool
agent_conversation
agent_message
agent_run
agent_run_checkpoint
agent_tool_invocation
platform_outbox_event
platform_inbox_event
workflow_agent_binding
```

`workflow_agent_binding` 是部署时生成的查询投影，用于记录流程定义、Activity 和 AgentVersion 的绑定并阻止误删。Agent 审计表不对 Flowable `ACT_*` 表建立强外键，避免流程历史清理破坏 Agent 审计记录。

## 14. 部署校验

部署包含 Agent 绑定的 BPMN 前必须校验：

1. AgentVersion 存在、已发布且属于当前租户或平台公共模板。
2. Provider 已启用，凭据可用但不在部署阶段执行真实模型请求。
3. 输入变量映射语法和输出 Schema 合法。
4. 节点工具是 AgentVersion 工具白名单的子集。
5. 超时、预算和失败策略在租户策略允许范围内。
6. Copilot 只绑定 User Task，自主模式只绑定受支持的可触发 Service Task。

校验失败时拒绝部署，不允许把错误延迟到流程运行期。

## 15. 可观测性

`traceId` 应贯穿 HTTP 请求、流程实例、AgentRun、Provider 请求和 ToolInvocation。

至少提供以下指标：

- Agent 运行成功率、失败率、取消率和超时率
- 排队时长、模型首包时间和总耗时
- 模型 Token、估算费用和租户配额使用率
- Provider 错误率和熔断状态
- 工具调用成功率和人工确认等待时长
- Outbox 积压、重试次数和最老事件时间
- Flowable 等待 Agent 的执行数量

## 16. 实施阶段

### 阶段 0：现有问题整改

先完成 `docs/quality/known-issues.md` 中的 P1 问题，尤其是流程锁重入、权限提权和租户恢复问题。

### 阶段 1：Agent 基础域

- Provider 与加密凭据
- AgentDefinition 与不可变 AgentVersion
- OpenAI Compatible 适配器
- 沙箱测试、发布和权限
- AgentRun、AgentCheckpoint、Worker 租约、Outbox/Inbox 和基础审计

### 阶段 2：自主 Agent 节点

- bpmn-js 属性配置
- 部署校验和绑定投影
- 异步可触发 Service Task
- 输入输出映射、重试、超时、失败路由和人工接管

### 阶段 3：人工任务 Copilot

- Task Conversation
- REST 命令与可恢复 SSE
- Agent 建议、结构化表单结果和人工确认
- 任务完成与 Agent 取消的并发处理

### 阶段 4：生产加固

- Testcontainers 故障与恢复测试
- 多租户隔离和权限矩阵测试
- 重复消息、服务重启、模型超时和流程并发测试
- 配额、指标、告警、压测和运维文档

## 17. 第一阶段验收标准

1. 两个租户可以配置不同 Provider 和密钥，且数据、日志和 API 不发生泄漏。
2. 已发布 Agent 版本不可修改，历史流程始终使用部署时绑定的版本。
3. 模型执行超过 Web 请求时长或服务重启后，流程仍可恢复并正确继续。
4. 相同完成事件重复投递不会重复触发流程节点。
5. Agent 输出不满足 Schema 时流程不继续，并进入可观察的失败处理。
6. Copilot 不能越权完成、驳回或转办人工任务。
7. 高风险工具未经人工确认不得执行。
8. PostgreSQL、Redis、Flowable 和 Agent Worker 的关键链路由 Testcontainers 集成测试覆盖。
9. 可以从审计记录还原 Agent 版本、模型、输入摘要、输出、工具调用、Token、费用和人工操作。
10. 在模型返回、工具请求、工具完成和等待人工四个边界强制终止 Worker 后，运行可以从最后检查点恢复且不重复执行写工具。

## 18. 已确定与后续演进

已确定：

- Flowable 与 Agent 运行时职责分离。
- 自主节点使用异步、可触发 Service Task 形成持久化等待态。
- Copilot 使用标准 User Task，最终完成权归人工。
- Agent 配置发布后不可变，BPMN 绑定具体版本。
- 第一阶段支持 OpenAI Compatible Provider 和租户 BYOK。
- 跨上下文使用可靠 Outbox/Inbox，不依赖内存事件。
- 工具由平台注册和治理，租户不能执行任意代码。

后续演进但不阻塞第一阶段：

- Vault/KMS 的具体产品适配。
- 用户 OAuth 委托与 Token Exchange。
- 远程 Agent 协议。
- 知识库与 RAG。
- 独立消息中间件。
- Agent 内部可视化编排。

## 19. 现有项目验证与价值判断

调研时间：2026-08-03。以下项目用于验证架构方向，不代表直接复制其实现。

| 项目 | 已验证能力 | 对本方案的结论 |
| --- | --- | --- |
| Flowable Enterprise Agent Engine | 独立 Agent Engine、BPMN AI Agent Task、结构化输入输出、设计期测试、审计以及非事务模型调用 | “Flowable 编排人和 Agent，Agent 作为流程参与者”的方向已被原厂产品验证；开源 Flowable Engine 中没有对应的完整 Agent 平台能力，本项目仍有开源实现价值 |
| Camunda AI Agent Connector | BPMN Agent Task、多 Provider、结构化响应、工具调用、人工反馈循环和 BPMN 显式治理 | 验证 BPMN 应作为外层权威编排，审批、补偿和工具边界应由流程结构表达，而不是只写在 Prompt 中 |
| Dify | Provider 插件、工作区凭据、发布流程、Human Input 节点、持久暂停恢复、Worker 执行和 Redis 实时事件 | 验证租户自助 Provider、草稿发布、异步运行和人机暂停恢复是成熟产品的必要能力 |
| LangGraph | 持久 Checkpoint、Interrupt、人工批准/编辑/拒绝以及故障恢复 | 证明长时 Agent 不能只保存最终结果；本方案因此增加 `AgentCheckpoint` 和可恢复状态机 |
| CIB seven AI Agent Connector | Java、LangChain4j、BPMN Service Task、OpenAI Compatible、工具、记忆和审计 | 直接验证 Java BPM 引擎接入 LangChain4j 的工程可行性；其密钥可来自流程变量、调用可处于引擎事务，本方案采用凭据引用和事务外执行以提高多租户安全性与可靠性 |
| LangChain4j | Java Provider 抽象、低层工具调用、持久化 Memory 扩展、结构化输出和 OpenAI Compatible | 适合作为模型协议和工具 Schema 库；平台仍需自行负责租户治理、持久状态机、审批、幂等和流程关联 |
| n8n | AI Agent 工具调用、敏感工具人工审批、流式交互和多种凭据 | 作为可自托管 fair-code 产品，验证工具分级审批的产品价值，但不作为本项目开源许可或 BPMN 架构基线 |

### 19.1 可实现性结论

方案可实现，且不存在必须自行研发模型推理协议的技术障碍。Flowable 原生异步可触发 Service Task 提供流程等待态，LangChain4j 提供 Java 模型和工具协议，PostgreSQL 可以承载 AgentRun、Checkpoint 与 Outbox，现有 Redis 用于流程锁和实时事件加速。

主要工程难点不在“调用一次 LLM”，而在以下边界：

1. Flowable 执行与 AgentRun 状态之间的幂等关联。
2. Worker 崩溃、重复事件和迟到结果下的恢复一致性。
3. 人工任务完成与 Agent 返回并发时的状态裁决。
4. 多租户凭据、工具权限、数据范围和费用隔离。
5. 工具写操作的审批、幂等和补偿。

这些难点均有成熟项目中的可参考模式，且可以在当前 Maven 多模块架构内分阶段实现。

### 19.2 方案优势

相比通用 AI 工作流平台，本方案的优势是 BPMN 长流程、人工任务、计时器、补偿、租户 RBAC 和流程审计；相比简单 BPM Agent Connector，本方案增加租户自助配置、不可变版本、可靠异步执行、检查点恢复和 Copilot 任务体验。

项目价值不在概念首创，而在提供一个基于 Flowable 8、Spring Boot 4 和 Java 的开源、可治理 Human-Agent Workflow 实现。最接近的完整原厂方案位于 Flowable Enterprise 产品，开源生态中现有方案通常只覆盖模型连接器或通用 AI DAG，尚不能替代本方案的目标组合。

### 19.3 需要避免的重复建设

- 不自行实现模型厂商协议、工具 Schema 编解码和基础消息对象，复用 LangChain4j。
- 不另做一套通用 Agent 画布，业务编排继续使用 BPMN。
- 不用内存会话模拟持久工作流，运行状态和检查点进入 PostgreSQL。
- 不把实时 SSE/Redis PubSub 当作事实存储，断线恢复以数据库事件序号为准。
- 不把安全规则只写进系统 Prompt，权限、审批和工具边界由后端及 BPMN 强制执行。

## 20. 参考依据

- [Flowable BPMN Constructs](https://www.flowable.com/open-source/docs/bpmn/ch07b-BPMN-Constructs/)：Triggerable Service Task 可在执行服务逻辑后保持等待；与 `async` 组合时先持久化流程状态，可避免外部响应抢跑。
- [Flowable Process Engine API](https://www.flowable.com/open-source/docs/bpmn/ch04-API/)：`RuntimeService` 用于恢复等待外部触发的流程执行。
- [Flowable Async Executor](https://www.flowable.com/open-source/docs/bpmn/ch18-Advanced/)：异步作业持久化与执行器行为说明。
- [Flowable AI Agent](https://documentation.flowable.com/latest/reactmodel/bpmn/reference/ai-agent)：Flowable Enterprise 的 BPMN AI Agent Task。
- [Flowable Agent Introduction](https://documentation.flowable.com/latest/reactmodel/agent/introduction/)：Agent 类型、审计以及异步和事务语义。
- [Camunda AI Agent Connector](https://docs.camunda.io/docs/components/connectors/out-of-the-box-connectors/agentic-ai-aiagent/)：BPMN Agent、工具循环和人工反馈模式。
- [Dify 1.13 Human-in-the-Loop release](https://github.com/langgenius/dify/releases/tag/1.13.0)：Human Input 的持久暂停、Worker 恢复与事件传输。
- [LangGraph Interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts)：持久检查点和人工中断恢复规则。
- [CIB seven AI Agent Connector](https://docs.cibseven.org/manual/latest/reference/connect/ai-agent-connector/)：Java BPMN、LangChain4j、工具、记忆和审计实现。
- [LangChain4j Tools](https://docs.langchain4j.dev/tutorials/tools/)：低层工具定义、执行和动态工具能力。
- [LangChain4j Structured Outputs](https://docs.langchain4j.dev/tutorials/structured-outputs/)：结构化输出与 JSON Schema 支持。
- [n8n AI Agent tools](https://github.com/n8n-io/n8n-docs/blob/main/docs/integrations/builtin/cluster-nodes/root-nodes/n8n-nodes-langchain.agent/tools-agent.md)：敏感工具调用的人工审批模式。
