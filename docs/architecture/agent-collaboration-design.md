# Agent 协作节点架构设计

更新时间：2026-08-05
状态：已验证设计基线，尚未实施

MVP 实施设计见[《Agent MVP 实施设计》](agent-mvp-implementation-plan.md)。本文负责长期架构边界，MVP 文档负责第一轮可开发任务拆分。

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
- 完整产品化的知识库、文档摄取、切分策略配置和检索质量评估平台。

这些能力需要在核心执行链路稳定后单独设计。

### 3.1 平台化实现边界

平台不能预先知道每个租户的业务材料、业务术语或企业系统接口，也不应把“读取材料”和“调用企业工具”写死在 `agent-engine` 中。平台提供通用运行时和治理能力，租户通过配置提供业务语义和数据连接。

| 责任方 | 负责内容 |
| --- | --- |
| 平台 | Agent 生命周期、模型适配、输入输出 Schema、工具注册、权限策略、凭据保护、异步执行、检查点、审计和成本统计 |
| 租户管理员 | Provider、凭据、数据源、工具授权、配额和安全策略 |
| 流程设计者 | Agent 版本、流程变量输入映射、文件上下文、输出映射、允许的工具子集和人工确认策略 |
| Agent Runtime | 根据已发布配置组装上下文、调用模型和工具、校验结构化结果并上报运行事件 |
| Flowable | 业务流程、人工任务、流程变量、任务状态和流程历史 |

Agent 节点必须通过显式契约接收业务输入，而不是自行扫描租户数据：

```text
Flowable 流程变量/文件
        ↓ inputMapping
Agent Runtime 上下文组装
        ↓
模型推理和受限工具调用
        ↓ outputSchema 校验
Agent 结果变量 / 人工复核任务
        ↓
Flowable 继续推进流程
```

第一阶段的 Agent 配置至少应包含：

```text
AgentDefinition
├── AgentDefinitionVersion
├── systemPrompt
├── modelRef
├── inputSchema / inputMapping
├── outputSchema / outputMapping
├── allowedTools
├── dataScopes
├── budgetPolicy
├── humanApprovalPolicy
└── retryTimeoutPolicy
```

企业工具采用平台注册、租户授权、Agent 版本选择的三级关系。第一阶段优先支持受治理的 OpenAPI/HTTP 和 MCP 连接器；禁止租户上传任意可执行代码或配置未经策略校验的任意目标地址。工具执行器负责注入租户凭据、校验参数、执行超时和幂等策略，模型只能看到工具 Schema，不能读取凭据。

平台不负责理解“请假”“采购”或“设备故障”等具体领域。领域知识通过流程变量、文件输入、租户授权的数据源和只读检索工具进入 Agent。领域模板可以后续增加，但不能成为 `agent-engine` 的硬编码依赖。

## 4. 核心原则

### 4.1 Flowable 是流程状态的唯一权威

流程实例、执行路径和人工任务状态只由 `workflow-engine` 通过 Flowable API 修改。`agent-engine` 只能提交运行结果事件，不能直接调用 `RuntimeService` 或 `TaskService`。

### 4.2 Agent 执行与流程事务隔离

模型和工具调用不得发生在 Flowable 数据库事务中。流程进入 Agent 节点时只持久化等待状态和运行请求，实际执行由事务外的 Agent Worker 完成。

### 4.3 Agent 输出是建议或结构化数据

Agent 输出必须通过 JSON Schema 校验和变量映射后才能进入流程上下文。涉及外部写操作、权限变更、付款、流程终止等高风险动作时，必须经过人工确认或确定性规则授权。

### 4.4 配置版本不可变

已发布的 Agent 版本不可修改。需要调整提示词、模型、工具或输出结构时必须发布新版本。已部署流程继续引用原版本，除非重新部署流程定义。

本方案中的“可复现”指能够还原运行时使用的 AgentVersion、模型、参数、Prompt 指纹、
工具 Schema、输入、证据、检索配置和策略版本，不承诺概率模型再次调用产生逐字相同输出。
Provider 未提供固定快照模型时，必须保存实际模型标识和 Provider 请求标识，并在审计中
标记外部模型可能发生漂移。

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
- 失败、空结果和部分结果策略
- 输入、输出、工具和证据 Guardrail 策略
- Grounding 策略与检索配置版本引用
- 上下文组装、截断和摘要策略
- 模型路由与降级策略
- 配置内容指纹

状态为 `DRAFT`、`PUBLISHED` 或 `RETIRED`。只有 `PUBLISHED` 版本可以部署到 BPMN。

### 7.3 AgentRun

表示一次可恢复执行。完整目标模型状态为：

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

MVP 只实现 `QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`TIMED_OUT` 和
`CANCELLED`。`WAITING_HUMAN`、`WAITING_TOOL_APPROVAL` 和
`CANCEL_REQUESTED` 属于后续人工交互扩展，不进入 MVP 的状态枚举和恢复逻辑。

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

`attempt` 不仅是计数值。每次重试必须创建独立的 `AgentRunAttempt`，原始尝试的
检查点、错误、租约和结束时间不得被覆盖。`AgentRun` 保存当前尝试引用和累计
尝试次数，`AgentRunAttempt` 保存每次尝试的完整审计。

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
- `checkpointSchemaVersion`、`attemptId`、`nextStep` 和 `stateHash`

服务重启或 Worker 租约过期后，从最后一个完整检查点继续。检查点只保存恢复所需的规范化状态，不保存 JVM 对象、函数或模型私有思维链。
不得在检查点、事件或普通审计字段中保存明文 Token、Provider 密钥、连接凭据或
模型隐藏思维链；敏感配置只能保存凭据引用，必要的敏感数据必须使用平台密钥加密。

### 7.6 ToolDefinition 与 ToolInvocation

工具由平台管理员注册，租户管理员授权，Agent 发布者选择。工具定义包含输入输出 Schema、风险级别、超时、幂等能力和凭据策略。

风险级别：

- `READ_ONLY`：只读查询。
- `REVERSIBLE_WRITE`：可补偿写操作。
- `HIGH_RISK_WRITE`：高风险操作，必须人工确认。

工具执行器负责注入凭据和再次校验参数。模型永远不能读取工具凭据。

### 7.7 KnowledgeRetrieval 与 ContextReference

知识检索是 Agent 的受控工具能力，不是 Agent Runtime 的核心状态机。第一阶段必须预留平台级抽象，但实现保持最小，避免后续被某个工单、设备或文档场景锁死。

Agent Runtime 只认识通用的检索请求和上下文引用，不直接依赖具体向量库、文档类型或业务表：

```text
KnowledgeSource
RetrievalQuery
RetrievalResult
ContextReference
Citation
```

第一阶段可以只提供内置 `knowledge.search` 只读工具，输入至少包含：

```json
{
  "query": "逆变器离线如何排查",
  "scopes": ["product_manual", "fault_case"],
  "topK": 5
}
```

检索结果必须使用稳定结构，至少包含：

```text
tenantCode
sourceType
sourceId
documentId
chunkId
contentPreview
score
rankScore
citation
metadata
permissionScope
indexVersion
```

第一阶段实现可以采用 PostgreSQL 全文检索、`pgvector` 或两者组合；也可以先接入现有内部知识检索服务。无论底层实现如何，Agent、BPMN 和工具调用审计只依赖上述稳定结果结构。

RAG 相关能力遵循“平台抽象要宽、首版实现要窄”的原则：

- `KnowledgeSource` 第一阶段可以只支持少量内置文档源，后续扩展到文档库、网页、历史工单、对象存储和业务系统投影。
- `Retriever` 第一阶段可以是 PostgreSQL 全文检索或 `pgvector` 向量召回，后续扩展到 BM25 + embedding 混合召回、cross-encoder rerank 和多路召回融合。
- `ChunkingStrategy` 第一阶段采用固定按标题和长度切分，后续按文档类型支持 PDF、表格、代码、SOP 和语义切分。
- `PermissionFilter` 第一阶段至少强制租户隔离和工具授权，后续扩展到部门、角色、流程实例、数据权限和字段级过滤。
- `Citation` 第一阶段保存 `documentId`、`chunkId` 和版本，后续扩展页码、坐标、原文快照和证据链。

PostgreSQL 可以作为第一阶段向量检索底座，但平台不应把领域模型绑定到 PostgreSQL 专有类型。只有在以下条件出现时才考虑额外接入专用向量库：单租户或全局索引规模超出 PostgreSQL 可接受范围、向量召回延迟无法满足 SLA、多模态或高维索引能力不足、需要独立横向扩缩容，或需要专用向量库提供的过滤、分片、在线重建和召回评估能力。

### 7.8 AgentRunStep 与统一结果语义

`AgentRun` 表示一次业务运行，`AgentRunAttempt` 表示一次执行尝试，
`AgentRunStep` 表示尝试中的一个可审计执行单元。第一阶段不实现多 Agent 编排，
但必须使用通用 Step 模型承载模型调用、工具调用、检索和结果校验，避免将所有过程
压入不可查询的 Checkpoint JSON。

```text
AgentRun
└── AgentRunAttempt
    ├── AgentRunStep(MODEL)
    ├── AgentRunStep(RETRIEVAL)
    ├── AgentRunStep(TOOL)
    └── AgentRunStep(VALIDATION)
```

后续子 Agent、并行分支和委派继续复用 `AgentRunStep`，通过 `parentStepId`、
`childRunId` 和独立命名空间表达，不修改 `AgentRun` 的基本语义。

所有 Provider、工具、检索器和校验器使用统一结果信封：

```text
AgentResultEnvelope<T>
├── resultStatus       # SUCCESS / EMPTY / PARTIAL / REJECTED / FAILED
├── data               # 可空，但必须与 resultStatus 一致
├── errorCode           # 失败或拒绝时必填
├── retryable
├── warnings
├── citations
├── qualitySummary
└── metadata
```

必须区分合法空集合、数据缺失、Provider 空响应、零召回、输出校验失败和技术异常。
禁止使用 `null` 同时表达上述多种语义，也禁止把错误文本伪装成正常工具结果重新注入
模型。每个 Step 的失败策略由 AgentVersion 冻结：必需步骤失败时可重试、降级、
转人工或让 BPMN 走失败路由；可选步骤允许以 `PARTIAL` 继续，但必须产生告警和审计。

`AgentCheckpoint` 负责恢复，`AgentRunStep` 负责执行账本，Trace 负责可观测性，三者
可以关联但不能互相替代。

`AgentRun.status` 只表达执行生命周期，`AgentRun.resultStatus` 表达业务结果质量。
例如运行可以执行完成但结果为 `PARTIAL`；是否允许其进入 `SUCCEEDED` 并恢复 Flowable，
必须由冻结的失败策略和质量门禁共同决定。

### 7.9 Guardrail、Grounding 与证据约束

模型幻觉不能被完全消除，平台的目标是限制无证据输出进入业务流程，并让风险可判定、
可路由和可审计。Guardrail 按执行边界分为：

- `INPUT`：输入分类、Prompt Injection 检测、数据脱敏和范围校验。
- `TOOL_INPUT`：工具参数 Schema、权限、风险等级和业务约束校验。
- `TOOL_OUTPUT`：响应大小、敏感字段、错误语义和不可信内容隔离。
- `OUTPUT`：JSON Schema、业务规则、引用完整性和允许字段校验。
- `GROUNDING`：关键事实是否有可访问、版本确定且足够的证据支持。

AgentVersion 必须引用不可变的 `GuardrailPolicy` 和 `GroundingPolicy`。GroundingPolicy
至少预留：证据是否必需、允许的数据源范围、最少证据数、最低检索质量要求、关键字段
引用要求，以及证据不足时 `ABSTAIN`、`FAIL` 或 `HUMAN_REVIEW` 的处理动作。

模型输出的自报置信度只作为审计信号，不能单独决定业务是否继续。平台应根据检索结果、
引用可访问性、确定性规则和人工反馈计算 `qualitySummary`。高风险写操作即使通过
Grounding 校验，也必须继续遵守工具权限和人工确认策略。

### 7.10 RetrievalTrace、评测集与质量门禁

每次 `knowledge.search` 必须生成 `RetrievalTrace`，记录原始查询、改写后查询、过滤条件、
TopK、命中结果、排序分数、耗时和所有相关版本：

```text
retrievalProfileVersion
indexVersion
embeddingModelVersion
chunkingStrategyVersion
rerankerVersion（可空）
queryRewriteVersion（可空）
```

线上相似度分数不能直接证明召回成功。检索质量必须基于版本化评测集，通过
`Recall@K`、`HitRate@K`、`MRR@K` 和 `nDCG@K` 判断召回与排序；最终回答另行评估
引用准确性、证据覆盖率、结构化输出正确率和人工接受率。所有指标按租户、知识范围、
语言、问题类型和配置版本分组，禁止只看全局平均值。

第一阶段只实现 RetrievalTrace 和最小离线回归测试接口，不建设完整评测产品。后续
`EvaluationDataset`、`EvaluationCase`、`EvaluationRun`、`Evaluator` 和人工反馈均保持
版本化。AgentVersion、模型、Prompt、工具或检索配置发布前，可通过质量门禁阻止相对
基线明显回退的版本进入生产；门禁阈值按场景配置，不设置脱离数据集的全局固定分数。

### 7.11 Provider 能力、模型路由与降级

Provider 适配器不得只抽象成一个 `chat` 方法。平台必须定义可查询的能力描述，至少包括：

- 流式响应、结构化输出、工具调用和并行工具调用。
- 图像、音频和文件输入能力。
- 上下文窗口、最大输出、Token 用量和 Provider 请求标识。
- 是否支持响应续接、取消、幂等键和批处理。
- 是否支持隐式或显式 Prompt/Prefix Cache、缓存键、保留时间、缓存 Token 明细，
  以及该缓存能力是否满足租户的数据保留和区域要求。

模型路由和降级必须由不可变策略控制并记录实际模型，不允许 Provider 异常后静默切换
模型。降级前必须校验能力兼容、数据驻留、租户授权、预算和输出 Schema；模型变化必须
进入 Trace、审计和评测维度。

### 7.12 记忆、子 Agent 与协议演进预留

- 记忆必须声明 `NONE`、`TASK`、`PROCESS_INSTANCE` 或后续租户允许的范围，同时具有
  版本、保留期限、摘要策略、删除能力和敏感信息过滤。记忆不是事实数据库。
- 上下文组装必须使用版本化 `ContextAssemblyPolicy`，定义来源优先级、各来源 Token
  预算、截断规则、摘要版本、重复内容消除和不可信内容边界。运行审计保存组装结果指纹
  和引用，不默认保存完整敏感正文。
- 子 Agent 后续通过父子 Run、Step 命名空间、深度限制、并发限制和独立预算接入；
  子 Agent 的空结果、失败和人工中断必须向父 Step 返回统一结果信封。
- MCP、OpenAPI、远程 Agent 和事件协议均必须带协议版本与能力协商。外部契约新增字段
  保持向后兼容，破坏性变化发布新版本。
- 多模态内容通过 `ContentReference` 和内容解析端口接入，不把大文件或二进制数据直接
  写入流程变量、事件或 Prompt 审计。

### 7.13 语义执行规范与运行优化边界

运行优化不得改变 Agent 的逻辑输入、权限、知识新鲜度或业务结果。平台将配置拆成三个
层次：

```text
AgentVersion
└── SemanticExecutionSpec（不可变）
    ├── Prompt、模型与参数
    ├── ContextAssemblyPolicy
    ├── 工具 Schema 与权限要求
    ├── RetrievalProfile 与数据新鲜度
    └── Guardrail、Grounding 和 FailurePolicy

TenantRuntimeOptimizationPolicy（版本化，可切换生效版本）
├── Provider Prompt/Prefix Cache 是否允许
├── 缓存模式、最长保留时间和数据驻留限制
├── Embedding/只读检索缓存是否允许
└── 灰度、禁用和故障旁路策略

AgentRun.EffectiveRuntimeSnapshot（运行事实）
├── semanticConfigFingerprint
├── runtimePolicyVersion
├── actualProvider / actualModel
├── cacheMode / cacheKeyHash / cacheHit
└── Token、费用、延迟和缓存节省明细
```

以下操作会改变模型看到的内容或业务数据新鲜度，属于语义行为，必须修改 AgentVersion
并经过评测后发布：

- 调整 Prompt 内容或消息顺序。
- 上下文裁剪、摘要、去重和历史窗口策略。
- Retrieval TopK、过滤、数据新鲜度、查询改写和 Rerank。
- 动态工具选择规则、模型路由和模型降级。
- Retrieval Cache、工具结果缓存对可接受陈旧时间的改变。

以下能力在满足约束时可以作为透明运行优化，不要求重新发布 AgentVersion：

- Provider 管理的 KV/Prompt/Prefix Cache。
- 相同内容、相同归一化版本和相同 Embedding 模型的 Embedding Cache。
- HTTP 连接复用、传输压缩、无语义变化的批处理和 Worker 调度优化。

透明优化必须遵守：

1. 缓存关闭、未命中或故障时能够旁路到标准执行路径，不能改变业务成功语义。
2. 命中与未命中使用相同逻辑输入；优化层不能修改 Prompt、工具参数或检索权限范围。
3. 缓存不是事实存储，不能保存唯一业务状态，也不能成为恢复 AgentRun 的必要条件。
4. 缓存键至少隔离租户、权限范围、Provider、实际模型、AgentVersion、工具 Schema、
   数据/索引版本和内容指纹；不得包含明文凭据和敏感正文。
5. Provider KV 数据由 Provider 或自托管推理服务管理，平台只保存能力、策略、键 Hash、
   命中和 Token 统计，不持久化或搬运 KV Tensor。
6. 缓存保留时间必须同时满足平台、租户、Provider 和数据分类策略；不满足零数据保留、
   区域驻留或删除要求时强制关闭。
7. 每种缓存提供平台总开关、租户开关、灰度比例、指标、故障旁路和一键回退。

缓存类型边界：

- Prompt/KV Cache 属于 Provider 优化，第一阶段只做能力协商和计量。
- Embedding Cache 可以后续按 `contentHash + embeddingModelVersion + normalizationVersion`
  实现。
- Retrieval Cache 必须加入 `tenantCode`、`permissionScope`、`retrievalProfileVersion`、
  `indexVersion` 和允许陈旧时间，不能作为纯透明缓存处理。
- 工具结果缓存只允许只读、确定性、声明可缓存且具有数据版本的工具。
- 最终回答语义缓存可能直接改变业务结果，不作为普通基础设施缓存，首个生产版本不实现。

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
- 错误统一分类为 `PROVIDER`、`TOOL`、`RETRIEVAL`、`VALIDATION`、`POLICY`、
  `BUDGET`、`CANCELLED` 和 `INTERNAL`，错误码稳定且与 Provider 原始消息解耦。
- `EMPTY`、`PARTIAL` 和 `REJECTED` 是业务结果，不等同于技术异常；是否允许继续由
  AgentVersion 的失败策略决定。
- 并行步骤允许保留已经成功的无副作用结果；存在写副作用时必须依据幂等或补偿策略
  决定重试范围，禁止无条件重放整个 AgentRun。

### 11.4 背压、公平调度与限额

- AgentRun 必须具有 `priority`、`availableAt` 和 `deadlineAt`，重试通过推进
  `availableAt` 实现，不允许 Worker 阻塞休眠占用线程。
- Worker 按租户和 Provider 执行并发限制，单个租户不能耗尽全部 Worker、数据库连接、
  Provider 配额或 Token 预算。
- Provider 的限流、`Retry-After`、熔断和恢复状态进入统一调度，不在业务代码中无限重试。
- 优先级只能在租户策略允许范围内设置，并通过老化或公平队列避免低优先级任务永久饥饿。
- 排队超过 `deadlineAt` 的运行不再调用模型，直接进入确定的超时或人工/BPMN 路由。

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
10. Provider Prompt/KV Cache、Embedding Cache 和其他运行缓存必须经过租户数据保留、
    区域驻留和数据分类策略校验；不兼容时以合规要求优先并关闭缓存。
11. 缓存清理、凭据停用、租户删除和数据删除请求必须具有可审计的失效流程；应用缓存
    不得绕过知识源权限变更或删除传播。

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
agent_run_attempt
agent_run_step
agent_run_checkpoint
agent_model_invocation
agent_tool_invocation
agent_guardrail_evaluation
agent_retrieval_trace
tenant_agent_runtime_policy
agent_knowledge_source
agent_knowledge_document
agent_knowledge_chunk
platform_outbox_event
platform_inbox_event
workflow_agent_binding
```

`workflow_agent_binding` 是部署时生成的查询投影，用于记录流程定义、Activity 和 AgentVersion 的绑定并阻止误删。Agent 审计表不对 Flowable `ACT_*` 表建立强外键，避免流程历史清理破坏 Agent 审计记录。

`agent_knowledge_*` 表只表示第一阶段最小检索底座和索引投影，不等同于完整知识库产品。若采用外部检索服务或专用向量库，这些表可以退化为知识源、索引版本和引用证据的本地投影。

`agent_run_step`、`agent_guardrail_evaluation` 和 `agent_retrieval_trace` 属于第一阶段
必须落库的运行事实。完整评测产品后续可以增加 `agent_evaluation_dataset`、
`agent_evaluation_case`、`agent_evaluation_run`、`agent_evaluation_result` 和
`agent_feedback`，但其关联键从第一阶段起固定为 `tenantCode`、`agentVersionId`、
`agentRunId`、`stepId`、配置版本和 Trace ID。

`agent_model_invocation` 保存每次模型调用实际使用的 Provider、模型、请求标识、Token、
费用、延迟和缓存统计；`tenant_agent_runtime_policy` 使用不可变版本记录租户允许运行优化
的边界。两者都不得保存 Provider KV Tensor、明文缓存键或敏感 Prompt 正文。

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
- 输入、输出、推理、缓存创建和缓存读取 Token，以及 Prompt Cache 命中率、节省费用和
  Prefill/首包延迟变化
- Provider 错误率和熔断状态
- 工具调用成功率和人工确认等待时长
- Step 的成功、空结果、部分结果、拒绝、重试和降级比例
- Guardrail 拒绝率、Grounding 失败率和无证据回答比例
- 检索零结果率、低质量结果率、Recall@K、MRR@K 和引用准确率
- AgentVersion 的离线评测基线、线上人工接受率和回退率
- Outbox 积压、重试次数和最老事件时间
- Flowable 等待 Agent 的执行数量

Trace 层级至少覆盖 AgentRun、Attempt、Step、模型调用、检索、工具、Guardrail 和
Flowable 恢复动作，并参考 OpenTelemetry GenAI 语义约定命名。Prompt、工具参数和模型
输出默认不进入 Trace 属性；仅在具备权限、脱敏并明确开启的诊断模式下保存受控快照。

## 16. 实施阶段

### 阶段 0：现有问题整改

先完成 `docs/quality/known-issues.md` 中的 P1 问题，尤其是流程锁重入、权限提权和租户恢复问题。

### 阶段 1：Agent 基础域

- Provider 与加密凭据
- AgentDefinition 与不可变 AgentVersion
- OpenAI Compatible 适配器
- 输入/输出 Schema、流程变量和文件的显式映射
- 平台工具注册、租户授权和工具白名单
- `knowledge.search` 只读工具、检索端口和标准引用结果结构
- 沙箱测试、发布和权限
- AgentRun、AgentCheckpoint、Worker 租约、Outbox/Inbox 和基础审计
- AgentRunStep、统一结果信封、错误分类和失败策略
- 输入/输出/工具基础 Guardrail 与 Grounding 扩展点
- RetrievalTrace 和检索配置版本记录

阶段 1 的验收重点是：两个租户可以使用不同的模型凭据、Agent 配置和工具授权完成同一类通用流程，平台不需要写入任何租户特定业务代码。

### 阶段 2：自主 Agent 节点

- bpmn-js 属性配置
- 部署校验和绑定投影
- 异步可触发 Service Task
- 输入输出映射、重试、超时、失败路由和人工接管
- 受治理的 OpenAPI/HTTP、MCP 工具连接器
- 工具调用前的租户权限、风险等级和幂等校验

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
- 版本化评测集、离线回归、线上反馈和发布质量门禁
- Prompt Injection、越权工具、错误引用和无证据输出的对抗测试

### 阶段 5：领域模板与生态扩展

只有通用平台契约稳定后，才按真实需求增加领域模板和连接器：

- 审批、工单、合同等场景的 Agent 模板
- 历史业务数据和外部知识库连接器
- 更细粒度的数据权限、字段过滤和证据链
- 专用模型 Provider、混合检索和 Rerank
- OAuth 委托、远程 Agent 和插件市场

领域模板只能复用平台公开的 Agent、Tool、Knowledge 和 Policy 契约，不得反向污染 `agent-engine` 的核心领域模型。

## 17. 首个生产版本验收标准（阶段 1-4）

本节描述完成自主 Agent、Copilot 和生产加固后的首个生产版本；更窄的 MVP 完成条件
以 19.3 节为准。

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
11. Provider、检索和工具的空返回、部分返回、拒绝和失败具有不同结果语义，并按
    AgentVersion 策略产生确定的重试、降级、人工接管或 BPMN 失败路由。
12. 使用知识检索的输出能够关联 RetrievalTrace 和 Citation；证据不足时不能以普通
    `SUCCEEDED` 将无证据事实写入流程变量。
13. 每次运行可以按 Attempt 和 Step 还原模型、检索、工具、校验和降级路径，且 Trace
    中默认不泄漏 Prompt、凭据和敏感业务数据。

## 18. 已确定、必须预留与后续演进

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
- 完整知识库产品、文档摄取、复杂切分、混合召回、rerank、检索质量评估和专用向量库适配。
- 独立消息中间件。
- Agent 内部可视化编排。

### 18.1 长期扩展能力地图

长期演进遵循“现在冻结兼容契约，按需求实现能力”的原则。下表中的“预留”不是现在
建设完整产品，而是保证未来扩展时不需要推翻 AgentVersion、AgentRun、Step、事件和
审计主模型。

| 能力域 | 现在必须冻结的契约 | 后续可扩展实现 |
| --- | --- | --- |
| 模型与 Provider | Provider 能力描述、实际模型记录、超时/取消、结构化输出和工具能力 | 多 Provider 路由、区域路由、批处理、推理模型和多模态 |
| 执行与恢复 | Run/Attempt/Step、Checkpoint、统一结果信封、错误分类、幂等键 | 并行 Step、子图、子 Agent、时间旅行和指定 Step 重放 |
| 失败与降级 | EMPTY/PARTIAL/REJECTED/FAILED 语义、失败策略、人工/BPMN 路由 | 模型降级、工具替代、补偿编排和自动故障转移 |
| Guardrail | 输入、工具输入、工具输出、最终输出和 Grounding 五个扩展点 | PII、内容安全、Prompt Injection、领域规则和外部审核器 |
| 工具与连接器 | 工具版本、Schema、权限、身份、风险、幂等、凭据引用 | MCP、OpenAPI、OAuth 委托、插件市场、远程执行沙箱 |
| 知识与 RAG | RetrievalProfile、RetrievalTrace、Citation、索引和配置版本 | 混合召回、Rerank、多路融合、多模态、专用向量库 |
| 评测与反馈 | Dataset/Case/Run/Evaluator 逻辑模型和运行关联键 | 离线实验、线上抽样、人工标注、A/B、发布门禁和回滚 |
| 记忆与上下文 | 作用域、保留期、版本、摘要和删除契约 | 长期记忆、用户画像、共享记忆和外部 Memory Provider |
| 人工协作 | 暂停原因、待办主体、恢复令牌和人工修改审计 | 多轮 Copilot、审批/编辑/拒绝、SLA 升级和委派 |
| 多 Agent | 父子 Run、parentStepId、命名空间、深度和预算限制 | Agent-as-tool、handoff、并行协作和领域专家团队 |
| 多模态 | ContentReference、媒体类型、解析器端口和对象存储引用 | OCR、表格、图像、音频、视频和实时语音 |
| 调度与容量 | priority、availableAt、deadlineAt、租户/Provider 并发和配额 | 公平队列、优先级老化、弹性 Worker、跨区域调度和容量预测 |
| Token 与运行优化 | SemanticExecutionSpec、RuntimeOptimizationPolicy、有效运行快照、缓存 Token 明细和旁路约束 | Provider Prefix Cache、Embedding Cache、受控 Retrieval Cache 和批处理优化 |
| 可观测性 | Trace/Span 关联、版本维度、敏感数据策略和成本统计 | OpenTelemetry 导出、质量看板、异常检测和容量预测 |
| 治理与合规 | 租户隔离、数据分类、留存删除、审计和配置不可变 | 数据驻留、法律保留、模型准入、合规导出和审计签名 |

### 18.2 接下来必须注意并实现（P0）

以下内容进入第一条 Agent 端到端链路，不能继续作为“以后再补”：

1. `AgentRun`、`AgentRunAttempt`、`AgentRunStep` 和 `AgentCheckpoint` 的职责与关联。
2. 统一结果信封，以及 `SUCCESS`、`EMPTY`、`PARTIAL`、`REJECTED`、`FAILED` 的语义。
3. 稳定错误分类、重试边界、写工具幂等和失败后 BPMN/人工路由。
4. AgentVersion 中冻结 `failurePolicy`、`guardrailPolicy`、`groundingPolicy`、
   `retrievalProfile` 和模型路由引用；首版可以使用受校验的 JSON 配置，但必须计算指纹。
5. 模型、工具和检索 Step 的输入输出 Schema 校验；错误内容不能伪装成正常结果。
6. `knowledge.search` 的 RetrievalTrace、Citation、租户权限和全部配置版本记录。
7. 基础 INPUT、TOOL_INPUT、TOOL_OUTPUT 和 OUTPUT Guardrail；使用检索时执行最低限度的
   Grounding 检查，证据不足必须拒答、失败或转人工。
8. Trace 覆盖 Run/Attempt/Step/Provider/Retrieval/Tool/Guardrail，默认不记录敏感正文。
9. 最小离线回归用例：固定输入、期望结构、期望引用和错误/空返回场景，纳入 CI。
10. AgentRun 的优先级、可执行时间、截止时间，以及租户和 Provider 的并发/配额限制。
11. `SemanticExecutionSpec` 与 `RuntimeOptimizationPolicy` 的边界、Provider 缓存能力描述、
    有效运行快照，以及输入/输出/推理/缓存创建/缓存读取 Token 的统一计量。
12. 缓存关闭和故障旁路路径；MVP 可以使用 No-op Cache Adapter，但接口不得假设缓存存在。

### 18.3 现在预留但暂不完整实现（P1）

以下内容只冻结接口、关联键和版本字段，不建设完整管理界面：

1. `EvaluationDataset`、`EvaluationCase`、`EvaluationRun`、`Evaluator` 和人工反馈模型。
2. Provider 能力协商、模型路由和受控降级；MVP 仍只执行一个明确模型。
3. 记忆保留、摘要、删除和 Memory Provider 端口；MVP 默认无跨流程长期记忆。
4. 子 Agent 的父子 Run、Step 命名空间、最大深度和预算传播；MVP 不允许子 Agent。
5. Human Interrupt 的暂停载荷、恢复令牌和人工修改审计；MVP 只通过 BPMN 失败路由接管。
6. 多模态 `ContentReference` 和内容解析端口；MVP 可只支持文本和文件引用。
7. 工具协议版本和能力协商；MVP 只开放受治理的只读工具。
8. Provider Prompt/Prefix Cache、Embedding Cache 和受新鲜度约束的 Retrieval Cache；
   接入顺序以前缀缓存计量、Embedding Cache、Retrieval Cache 为先后。

### 18.4 明确后做（P2）

- 多 Agent 自主协商、通用 Agent 子流程画布和复杂计划器。
- 自动生成或自动修改生产 Prompt、策略和评测基线。
- 任意代码插件、租户自定义运行时和未经审核的外部 MCP 服务。
- 完整知识库产品、自动文档清洗、复杂 Rerank 服务和专用向量数据库集群。
- 全自动 LLM-as-a-Judge 决定高风险业务结果；评审模型只能作为质量信号。
- 最终回答语义缓存、跨租户共享私有上下文缓存和自动复用历史业务结论。

### 18.5 兼容性红线

- 不把某个模型厂商的响应对象作为领域模型或事件载荷。
- 不把向量数据库主键、分数算法或专有过滤语法暴露给 BPMN。
- 不允许新增 Provider、工具或子 Agent 绕过统一 Step、Guardrail、权限和审计链路。
- 不允许发布后静默修改 Prompt、模型、工具、检索配置、评测器或策略。
- 不以模型自报置信度、单次人工点赞或线上相似度分数作为唯一质量判据。
- 不自动使用生产反馈训练、修改 Prompt 或扩充知识库，必须经过脱敏、审核和版本发布。
- 不允许为了提高缓存命中率在运行时偷偷调整 Prompt 顺序、裁剪上下文、降低检索新鲜度
  或切换模型；这些变化属于语义配置，必须发布新 AgentVersion。
- 不允许把缓存作为 AgentRun 恢复、流程推进或审计还原的唯一数据来源。

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

### 19.3 Agent MVP 可开发规格

本节将前述架构收敛为第一版可以直接拆分任务的实现规格。MVP 只验证一条完整闭环：

```text
已发布 Agent 配置
    ↓
Flowable Agent Service Task
    ↓
可靠事件创建 AgentRun
    ↓
Agent Worker 调用 OpenAI Compatible Provider
    ↓
JSON Schema 校验输出
    ↓
AgentRunCompleted / AgentRunFailed
    ↓
workflow-engine 校验并恢复 Flowable 流程
```

#### 19.3.1 MVP 范围

必须实现：

1. 单租户范围内的 `AgentDefinition`、不可变 `AgentVersion` 和发布状态。
2. `OPENAI_COMPATIBLE` Provider，以及服务端加密的租户凭据引用。
3. 一个自主 Agent BPMN 节点，使用异步、可触发 Service Task。
4. 流程变量和文件引用到 Agent 输入的显式映射。
5. JSON Schema 输出校验和结果到流程变量的显式映射。
6. `AgentRun`、`AgentCheckpoint`、Outbox/Inbox、租约和基础审计。
7. `QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED`、`TIMED_OUT` 六种运行状态。
8. 重试、超时、取消、服务重启恢复和重复完成事件幂等。
9. 一个只读工具 `knowledge.search`，工具执行先经过租户授权和参数校验。
10. `AgentRunStep`、统一结果信封、错误分类和空/部分结果策略。
11. 基础 Guardrail、检索 Trace、Citation 和最小 Grounding 校验。
12. 固定样例的结构化输出、失败语义和检索引用回归测试。
13. Provider 缓存能力描述、模型调用 Token 明细、运行优化策略版本和 No-op Cache
    旁路实现；MVP 不要求真正启用 KV/Prompt Cache。

明确不进入 MVP：

- 多 Agent 协作。
- Agent 内部可视化子流程。
- Copilot 多轮会话和跨流程记忆。
- 高风险写工具、自动审批、自动转办和自动终止流程。
- 任意代码、任意 URL 和租户自定义插件上传。
- 完整知识库产品、复杂切分、Rerank 和专用向量数据库。

#### 19.3.2 AgentRun 状态机

```text
QUEUED ──领取租约──> RUNNING
  │                    │
  │                    ├──成功──> SUCCEEDED
  │                    ├──可重试失败──> QUEUED
  │                    ├──最终失败──> FAILED
  │                    ├──超时──> TIMED_OUT
  │                    └──取消请求──> CANCELLED
  │
  └──取消请求──> CANCELLED
```

状态转换只能由 `AgentRunStateMachine` 执行，禁止 Controller、Provider 或 Worker 直接修改任意状态。每次转换必须保存：旧状态、新状态、原因、操作者或系统主体、时间和 `traceId`。

状态约束：

- `QUEUED` 只能由创建运行、可重试失败或租约回收进入。
- `RUNNING` 只能由持有有效租约的 Worker 进入。
- `SUCCEEDED`、`FAILED`、`CANCELLED`、`TIMED_OUT` 是终态。
- 终态收到重复完成或失败事件时返回幂等成功，不再次推进 Flowable。
- `WAITING_HUMAN` 暂不进入 MVP；后续人工交互使用独立版本扩展状态机。

#### 19.3.3 MVP 数据模型

第一版使用 PostgreSQL，所有业务表必须包含 `tenant_code`、创建时间、更新时间和必要的审计字段。建议最小字段如下：

```text
agent_definition
├── id
├── tenant_code
├── code
├── name
├── description
├── status
└── created_by

agent_definition_version
├── id
├── tenant_code
├── definition_id
├── version
├── status                 # DRAFT / PUBLISHED / DISABLED
├── model_ref
├── system_prompt
├── input_schema_json
├── input_mapping_json
├── output_schema_json
├── output_mapping_json
├── tool_policy_json
├── budget_policy_json
├── retry_policy_json
├── failure_policy_json
├── guardrail_policy_json
├── grounding_policy_json
├── retrieval_profile_ref
├── context_assembly_policy_json
├── model_routing_policy_json
├── config_fingerprint
├── published_by
└── published_at

tenant_agent_runtime_policy
├── id
├── tenant_code
├── version
├── provider_ref             # 可空，空表示租户默认
├── prompt_cache_mode        # DISABLED / PROVIDER_MANAGED / EXPLICIT
├── max_cache_retention
├── embedding_cache_allowed
├── retrieval_cache_allowed
├── zero_data_retention_required
├── data_residency_policy_json
├── rollout_percentage
├── config_fingerprint
├── effective_at
└── status

agent_run
├── id
├── tenant_code
├── agent_version_id
├── semantic_config_fingerprint
├── runtime_policy_version
├── process_definition_id
├── process_instance_id
├── execution_id
├── task_id                # 可空
├── activity_id
├── activity_activation_id
├── status
├── result_status
├── idempotency_key
├── priority
├── available_at
├── deadline_at
├── attempt_count
├── current_attempt_id
├── lease_owner
├── lease_expires_at
├── input_snapshot_json
├── output_snapshot_json
├── error_code
├── error_message
├── started_at
├── finished_at
└── trace_id

agent_run_attempt
├── id
├── tenant_code
├── agent_run_id
├── attempt_no
├── status
├── lease_owner
├── lease_expires_at
├── input_snapshot_json
├── output_snapshot_json
├── error_code
├── error_message
├── started_at
└── finished_at

agent_run_step
├── id
├── tenant_code
├── agent_run_id
├── attempt_id
├── parent_step_id          # 可空
├── child_run_id            # 预留，可空
├── sequence_no
├── step_type               # MODEL / RETRIEVAL / TOOL / VALIDATION
├── step_code
├── status
├── result_status           # SUCCESS / EMPTY / PARTIAL / REJECTED / FAILED
├── error_code
├── retryable
├── input_snapshot_json
├── output_snapshot_json
├── started_at
└── finished_at

agent_run_checkpoint
├── id
├── tenant_code
├── agent_run_id
├── attempt_id
├── sequence_no
├── checkpoint_schema_version
├── checkpoint_type       # MODEL_RESPONSE / TOOL_REQUEST / TOOL_RESULT / HUMAN_PAUSE
├── next_step
├── state_json
├── state_hash
├── created_at
└── unique(agent_run_id, attempt_id, sequence_no)

agent_model_invocation
├── id
├── tenant_code
├── agent_run_id
├── attempt_id
├── step_id
├── provider_ref
├── actual_model
├── provider_request_id
├── semantic_config_fingerprint
├── runtime_policy_version
├── cache_mode
├── cache_key_hash           # 可空，不保存明文缓存键
├── cache_hit
├── input_tokens
├── cache_creation_input_tokens
├── cache_read_input_tokens
├── output_tokens
├── reasoning_tokens
├── estimated_cost
├── actual_cost
├── first_token_latency_ms
├── total_latency_ms
├── status
└── created_at

agent_tool_invocation
├── id
├── tenant_code
├── agent_run_id
├── attempt_id
├── tool_code
├── idempotency_key
├── risk_level
├── request_json
├── response_json
├── status
├── started_at
└── finished_at

agent_guardrail_evaluation
├── id
├── tenant_code
├── agent_run_id
├── attempt_id
├── step_id
├── guardrail_type          # INPUT / TOOL_INPUT / TOOL_OUTPUT / OUTPUT / GROUNDING
├── policy_version
├── decision                # ALLOW / REJECT / HUMAN_REVIEW
├── reason_code
├── result_summary_json
└── created_at

agent_retrieval_trace
├── id
├── tenant_code
├── agent_run_id
├── attempt_id
├── step_id
├── retrieval_profile_version
├── index_version
├── embedding_model_version
├── chunking_strategy_version
├── reranker_version        # 可空
├── query_rewrite_version   # 可空
├── query_snapshot_json
├── filter_snapshot_json
├── result_snapshot_json
├── latency_ms
└── created_at
```

MVP 不对 Flowable `ACT_*` 表建立数据库外键。Agent 表通过
`process_definition_id`、`process_instance_id`、`execution_id`、`activity_id` 和
`activity_activation_id` 建立可查询关联，避免历史清理或流程引擎升级破坏 Agent
审计数据。必须建立以下租户范围内的唯一约束：

```text
unique(tenant_code, idempotency_key)
unique(tenant_code, process_instance_id, execution_id,
       activity_activation_id, agent_version_id)
unique(tenant_code, agent_run_id, attempt_no)
unique(tenant_code, agent_run_id, attempt_id, sequence_no)
unique(tenant_code, provider_ref, version) on tenant_agent_runtime_policy
unique(tenant_code, provider_ref, provider_request_id) where provider_request_id is not null
```

第二个约束用于防止同一节点激活重复创建运行；循环再次进入同一 BPMN 节点时，
必须生成新的 `activity_activation_id`。

#### 19.3.4 可靠事件协议

事件统一使用共享事件信封：

```json
{
  "eventId": "uuid",
  "eventType": "AgentRunRequested.v1",
  "occurredAt": "2026-08-05T10:00:00Z",
  "traceId": "trace-id",
  "tenantCode": "tenant-a",
  "aggregateId": "agent-run-id",
  "payload": {}
}
```

MVP 只实现以下事件：

| 事件 | 发布者 | 消费者 | 语义 |
| --- | --- | --- | --- |
| `AgentRunRequested.v1` | workflow-engine | agent-engine | 请求执行已经创建的 AgentRun |
| `AgentRunCancellationRequested.v1` | workflow-engine | agent-engine | 请求取消未完成运行 |
| `AgentRunCompleted.v1` | agent-engine | workflow-engine | Agent 输出已通过必需的 Schema、Guardrail、Grounding 和失败策略校验 |
| `AgentRunFailed.v1` | agent-engine | workflow-engine | Agent 进入失败或超时终态 |

Outbox 记录至少包含 `event_id`、`event_type`、`aggregate_id`、`tenant_code`、`payload`、`status`、`attempt_count`、`next_attempt_at` 和 `last_error`。Inbox 以 `event_id` 建立唯一约束，消费者必须先去重再执行业务动作。

事件职责固定为：workflow-engine 在同一事务中创建 `AgentRun` 并写入
`AgentRunRequested` Outbox；agent-engine 只负责领取和执行已有运行，不负责通过该
事件隐式创建运行。完成、失败和取消事件必须携带 `agentRunId`、`attemptId`、
`activityActivationId` 和结果摘要，消费方据此执行二次幂等校验。

#### 19.3.5 Flowable 交互时序

```text
Flowable 进入 Agent Service Task
        ↓
agentTaskDelegate 只校验 binding 和输入映射
        ↓
事务内创建 AgentRun(QUEUED) + Outbox
        ↓
事务提交，Triggerable Service Task 以 `async` 方式持久化流程等待态
        ↓
Outbox Dispatcher 发布 AgentRunRequested
        ↓
Agent Worker 领取租约并进入 RUNNING
        ↓
模型调用 / 检查点 / 工具执行
        ↓
Agent 发布 Completed 或 Failed
        ↓
workflow-engine 获取流程实例锁
        ↓
校验 AgentRun、租户、流程实例、Activity 和版本
        ↓
在流程实例锁和事务执行器内写入流程变量，并通过 `RuntimeService` 恢复等待执行
```

关键约束：

- 模型和工具调用不在 Flowable 事务或流程实例锁内执行。
- `AgentRunCompleted` 不能直接调用 Flowable；必须由 workflow-engine 的事件消费者执行。
- 事件消费者必须确认 AgentRun 当前属于对应流程实例和 Activity，迟到结果不得修改已经离开该节点的流程。
- Flowable 恢复动作必须使用现有流程实例锁和事务执行器；实现上由消费者定位
  对应的等待执行，校验 `activityActivationId` 后调用 `RuntimeService` 恢复，不能
  直接在消息线程中绕过事务执行器调用 Flowable。
- Flowable 恢复遇到乐观锁冲突时只能重试消费，不得重复写入结果；超过重试上限进入
  运营告警和待处理状态。
- Agent 结果写入流程变量前必须执行输出 Schema、变量名称和大小限制校验。
- 流程实例已经离开该节点、Agent 版本不匹配、租户不匹配或尝试编号不是当前尝试
  时，事件只能记录为迟到或过期事件，不得推进流程。

#### 19.3.6 MVP API 契约

管理端 API：

```text
POST /agent/definitions
GET  /agent/definitions
POST /agent/definitions/{id}/versions
POST /agent/versions/{id}/publish
POST /agent/versions/{id}/disable
POST /agent/versions/{id}/test
```

运行与运维 API：

```text
GET  /agent/runs/{runId}
POST /agent/runs/{runId}/cancel
POST /agent/runs/{runId}/retry
GET  /agent/runs/{runId}/checkpoints
```

API 规则：

- 所有查询和命令自动使用当前主体的 `tenantCode`，请求体不得覆盖租户。
- 发布前校验 Provider、凭据引用、Schema、映射、工具白名单和预算策略。
- `retry` 只能作用于 `FAILED`、`TIMED_OUT`，且必须生成新的尝试记录，不得覆盖原始审计。
- `cancel` 对终态返回幂等成功，对运行中的 Agent 写入取消事件。
- 测试运行必须使用独立的 `test` 标记，不得写入生产流程变量或触发业务工具。

所有 API 必须定义稳定的请求 DTO、响应 DTO、分页字段和错误码。错误响应统一包含
`code`、`message`、`traceId`，禁止把 Provider 原始异常或凭据相关信息直接返回给前端。
运行详情至少返回 Agent 版本、流程关联、当前尝试、状态、时间、错误摘要和可见检查点；
原始 Prompt、凭据引用和敏感输入必须按权限脱敏。

#### 19.3.7 实施任务拆分

建议按以下顺序实现，每个任务完成后保持可编译和可测试：

1. `shared-kernel`：事件信封、租户字段、Trace ID 和错误码。
2. `agent-engine`：AgentDefinition、AgentVersion、Provider、凭据引用和状态机。
3. `platform-migrations`：MVP 表、索引、唯一约束和 Flyway 集成测试。
4. `agent-engine`：AgentRun、Attempt、Step、Checkpoint、统一结果信封、错误分类、
   租约、优先级、截止时间、租户/Provider 限额、Outbox/Inbox 和 Worker 接口。
5. `workflow-engine`：Agent Service Task Binding、输入输出映射和部署校验。
6. `workflow-engine`：AgentRun 完成/失败事件消费者和 Flowable 恢复命令。
7. `agent-engine`：OpenAI Compatible Provider、Provider 能力描述、模型 Step、
   `agent_model_invocation` Token/费用明细和 No-op Cache Adapter。
8. `agent-engine`：`knowledge.search`、RetrievalTrace、Citation 和检索 Step。
9. `agent-engine`：基础 Guardrail、Grounding、失败策略和验证 Step。
10. `workflow-boot`：事件调度、Worker 装配、配置、Trace 和健康检查。
11. 前端：Agent 定义、版本发布、流程节点绑定和运行详情页面。
12. 测试：重复事件、租约过期、Worker 重启、超时、取消、跨租户、迟到事件、
    空结果、部分结果、Guardrail 拒绝和检索证据不足。

实现顺序补充约束：先冻结状态机、`AgentRunAttempt`、事件信封和 Flowable 恢复协议，
再开始 Provider、工具连接器和前端。第一条端到端链路必须先完成“创建运行 → 异步执行
→ 检查点 → 完成事件 → Flowable 恢复”的闭环，再扩展更多工具和人工交互。

#### 19.3.8 MVP 完成判定

只有满足以下条件，才进入人工 Copilot 或更多工具连接器：

1. 两个租户可以配置不同 Agent 版本和 Provider，数据与凭据不会交叉读取。
2. 一个 BPMN Agent 节点可以在 Web 请求结束后继续执行并恢复流程。
3. Worker 被终止后可以从最后检查点恢复，幂等读工具不会重复产生业务副作用。
4. Agent 输出错误、超时、取消和重复完成事件都有确定结果。
5. 流程已经离开 Agent 节点后，迟到事件不能修改流程变量或重新推进流程。
6. 可以通过 AgentRun 和 Flowable 历史还原一次运行的版本、输入摘要、模型、工具调用、输出和最终流程结果。
7. PostgreSQL、Redis、Flowable 和 Worker 关键链路通过 Testcontainers 集成测试。
8. 每次 AgentRun 可以按 Attempt 和 Step 还原执行路径，Checkpoint 与 Step 职责没有
   混用，错误文本不会以正常数据写入模型上下文或流程变量。
9. `knowledge.search` 的每次调用都有 RetrievalTrace 和 Citation；固定评测样例可以
   发现检索配置或 Prompt 变更导致的引用回退。
10. 必需步骤返回 `EMPTY`、`PARTIAL`、`REJECTED` 或证据不足时，能够按照发布版本中
    冻结的策略得到确定结果，不会被误判为普通成功。
11. 一个租户或 Provider 达到并发/配额上限时，其他租户仍能领取和执行任务；超过
    `deadlineAt` 的运行不会继续消耗模型 Token。
12. 关闭缓存、缓存未命中和缓存适配器故障都走同一标准执行语义；模型调用能够记录
    普通输入、缓存创建、缓存读取、输出和推理 Token，且不会跨租户复用私有上下文。

### 19.4 需要避免的重复建设

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
- [OpenAI Agents SDK Guardrails](https://openai.github.io/openai-agents-python/guardrails/)：输入、输出和工具级 Guardrail 的执行边界与拒绝语义。
- [OpenAI Agents SDK Tracing](https://openai.github.io/openai-agents-python/tracing/)：Agent、模型、工具、Guardrail 和 Handoff 的分层 Trace 模型。
- [LangGraph Persistence](https://docs.langchain.com/oss/python/langgraph/persistence)：Checkpoint、失败恢复、状态检查和人工中断的持久化语义。
- [LangGraph Subgraphs](https://docs.langchain.com/oss/python/langgraph/use-subgraphs)：子任务命名空间、独立调用和父子状态持久化边界。
- [LangGraph Fault Tolerance](https://docs.langchain.com/oss/python/langgraph/fault-tolerance)：节点重试、错误处理、子图失败传播和恢复安全约束。
- [LangSmith Evaluation Concepts](https://docs.langchain.com/langsmith/evaluation-concepts)：版本化数据集、离线/在线评测和按组件拆分质量指标。
- [LangSmith RAG Evaluation](https://docs.langchain.com/langsmith/evaluate-rag-tutorial)：检索相关性、回答质量和评测数据集的分层评估方法。
- [OpenTelemetry GenAI Semantic Conventions](https://opentelemetry.io/docs/specs/semconv/registry/attributes/gen-ai/)：模型、Agent、工具、检索和 Token 等 Trace 属性的通用命名基线。
- [OpenAI Responses API Prompt Cache](https://platform.openai.com/docs/api-reference/responses-streaming/response/refusal/delta)：缓存键、缓存保留时间和 `cached_tokens` 用量明细。
- [OpenAI Data Controls](https://platform.openai.com/docs/models/default-usage-policies-by-endpoint)：扩展 Prompt Cache 与数据保留、区域处理和零数据保留的约束。
- [vLLM Automatic Prefix Caching](https://docs.vllm.ai/en/stable/design/prefix_caching/)：由推理服务管理 KV Block 和相同前缀复用，平台不应自行持久化 KV Tensor。
- [n8n AI Agent tools](https://github.com/n8n-io/n8n-docs/blob/main/docs/integrations/builtin/cluster-nodes/root-nodes/n8n-nodes-langchain.agent/tools-agent.md)：敏感工具调用的人工审批模式。
