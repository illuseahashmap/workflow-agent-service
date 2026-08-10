# 证据驱动的 Agent 渐进式自治设计方向

状态：后续设计方向，尚未实现。

本文收敛“让 Agent 使用运行证据逐步获得任务完成权”的长期方向。它建立在现有
`AgentVersion`、`AgentRun`、Attempt、Step、Checkpoint、Outbox/Inbox 和 Flowable
任务能力之上，不改变当前 Agent Runtime 生产可靠性问题优先处理的顺序。

## 1. 定位

平台不把“能够在 BPMN 中调用 Agent”作为最终差异化，而是解决以下问题：

> 一个 Agent 在什么证据、风险约束和责任边界下，可以从后台观察逐步升级为建议、
> 受监督执行，最终获得特定任务的受约束完成权；当质量回退时，平台如何及时降低其
> 权限并恢复人工处理。

核心能力称为“证据驱动的渐进式自治”。它不是新的流程引擎，也不是自定义 Flowable
执行语义，而是建立在标准 User Task、Agent Runtime 和平台权限体系之上的治理层。

## 2. 目标与非目标

### 2.1 目标

1. Agent 必须通过真实任务证据证明质量，不能仅凭模型自报置信度获得完成权。
2. 自治等级必须可配置、可审计、可回放、可升级、可降级并绑定确定版本。
3. 同一个 Agent 在不同租户、流程、任务和风险区间可以具有不同的自治等级。
4. 人工决定、Agent 建议、最终生效决定和后续业务结果必须形成可关联的证据链。
5. 新 Agent、模型、Prompt、知识和工具版本必须能够通过历史案例回放后再进入更高自治等级。
6. 高风险操作、租户安全策略和 BPMN 声明上限始终优先于质量评分。

### 2.2 非目标

- 不新增与 Flowable 竞争的任务状态机。
- 不让 `agent-engine` 直接调用 `TaskService` 完成、驳回或转办任务。
- 不根据少量样本或单一平均指标自动授予生产完成权。
- 不保存模型私有思维链，也不默认保存未经脱敏的完整业务正文。
- 第一阶段不自动提升自治等级，只提供证据、建议和人工审批入口。
- 不把 RAG、MCP、多 Agent 或自然语言生成 BPMN 本身当作该方向的核心亮点。

## 3. 自治等级

| 等级 | 名称 | Agent 行为 | 任务完成权 |
| --- | --- | --- | --- |
| L0 | `HUMAN_ONLY` | 不运行 Agent | 仅人工 |
| L1 | `SHADOW` | 后台生成建议，仅用于比较和评测，默认不向处理人展示 | 仅人工 |
| L2 | `ADVISORY` | 向处理人展示建议、证据、风险和结构化表单草稿 | 仅人工 |
| L3 | `SUPERVISED` | Agent 形成待提交决定，人工确认或修改后提交 | 人工确认后生效 |
| L4 | `CONSTRAINED_AUTONOMOUS` | 仅在策略允许的低风险范围内自动完成，其他情况转人工 | 平台受约束授予 |

暂不定义无边界的 `FULL_AUTONOMOUS`。生产业务中的 Agent 始终受到租户权限、任务风险、
工具权限、预算、Guardrail、Schema、Grounding 和 BPMN 声明上限约束。

`SHADOW`、`ADVISORY` 等是协作目的或有效自治等级，不是 `AgentRunStatus`。现有
`QUEUED / RUNNING / SUCCEEDED / FAILED / TIMED_OUT / CANCELLED` 状态机保持不变。

## 4. 完成权计算

任务激活时，由 `workflow-engine` 请求自治策略解析，得到本次任务的有效自治等级：

```text
effectiveAutonomyLevel = min(
    BPMN 声明的最大自治等级,
    当前已发布策略版本的等级,
    租户安全策略允许的等级,
    当前任务风险等级允许的等级,
    当前 AgentVersion 的质量门禁等级
)
```

这里的 `min` 表示取最严格边界，不表示枚举数值的直接数学计算。任一硬性规则要求人工
处理时，有效等级必须降为 `HUMAN_ONLY`、`ADVISORY` 或 `SUPERVISED`。

模型自报置信度只能参与证据分析，不能单独决定完成权。付款、权限变更、合同签署、流程
终止和租户定义的高风险动作不得因历史一致率较高而绕过人工确认。

## 5. BPMN 绑定

不创建破坏 BPMN 兼容性的自定义任务类型。平台继续使用标准 User Task，并通过
`extensionElements` 声明 Agent 和自治边界。建议的方向性结构如下，最终 XML Schema
在实现任务中确定：

```xml
<userTask id="invoiceReview" name="发票复核" flowable:assignee="${reviewer}">
    <extensionElements>
        <agent:binding agentVersionId="1024"
                       autonomyPolicyKey="invoice-review-policy"
                       maximumAutonomy="SUPERVISED"
                       inputMapping="invoice-review-input-v1"
                       outputMapping="invoice-review-output-v1" />
    </extensionElements>
</userTask>
```

`autonomyPolicyKey` 引用逻辑策略，任务每次激活时解析当时已发布的不可变
`AutonomyPolicyVersion`，并把实际版本写入运行和决策证据。这样策略可以逐步升级，
同时历史实例仍能还原当时真正生效的规则。

AgentVersion 继续由 BPMN 绑定具体不可变版本。候选版本的影子验证和历史回放由独立的
评测配置触发，不静默替换生产绑定版本。

## 6. 领域边界

### 6.1 复用现有模型

- `AgentDefinition`、`AgentVersion`：描述 Agent 能力和不可变配置。
- `AgentRun`、Attempt、Step、Checkpoint：描述一次 Agent 执行及恢复过程。
- 模型调用、工具、检索、Guardrail 审计：提供 Agent 执行证据。
- Outbox/Inbox：跨 `workflow-engine` 与 `agent-engine` 可靠传递事件。
- Flowable 历史和平台操作审计：提供任务和流程事实。

### 6.2 新增概念

| 概念 | 职责 |
| --- | --- |
| `AutonomyPolicy` | 租户内稳定的策略标识 |
| `AutonomyPolicyVersion` | 不可变的自治等级、风险条件、样本门槛和回退条件 |
| `WorkflowAgentBinding` | 流程定义、Activity、AgentVersion 和策略键的部署投影 |
| `TaskDecisionEvidence` | 一次任务激活对应的不可变决策证据聚合 |
| `AgentProposal` | Agent 的结构化建议、依据摘要、质量和版本信息 |
| `HumanDecision` | 人工最终选择、修改内容和反馈原因 |
| `OutcomeObservation` | 任务完成后的退回、撤销、投诉、损失或其他业务结果 |
| `AutonomyEvaluationRun` | 对策略、AgentVersion 或历史样本执行的评测批次 |
| `AutonomyLevelChange` | 自治等级升级、降级、冻结及其审批证据 |

上述名称是领域概念，不要求第一轮逐概念建表。实现前应根据查询、生命周期和一致性边界
决定聚合与表结构，避免把人工决定和下游结果直接堆入 `agent_run`。

### 6.3 模块职责

- `workflow-engine`：拥有任务生命周期、任务权限、任务锁、最终决定和 Flowable 状态修改权。
- `agent-engine`：拥有 Agent 执行、建议生成、自治策略、评测和 Agent 侧证据。
- `workflow-engine` 通过事件提供任务创建、人工完成和后续流程结果；`agent-engine` 不依赖
  Flowable Java API，只保存不带强外键的业务引用。
- 当自治策略允许自动完成时，仍由 `workflow-engine` 校验任务活跃状态、当前处理权限、
  幂等键和风险边界，再以明确的系统 Agent 操作者身份完成任务。

## 7. 决策证据

每次绑定 Agent 的 User Task 激活都生成稳定的 `taskActivationId`。一次完整证据至少关联：

```text
tenantCode
processDefinitionId / processDefinitionVersion
processInstanceId / taskId / activityId / taskActivationId
workflowAgentBindingId
agentVersionId / agentRunId
autonomyPolicyVersionId / effectiveAutonomyLevel
inputSnapshotFingerprint
agentProposal / proposalQualitySummary
humanDecision / effectiveDecision
completionActorType / completionActorId
outcomeObservations
traceId / occurredAt
```

证据记录遵循以下规则：

1. Agent 建议、人工决定和最终生效决定分别保存，不能互相覆盖。
2. 人工修改或拒绝建议时记录结构化原因，允许补充说明但不强迫记录敏感正文。
3. Agent 迟到结果只进入审计，不得修改已经完成的任务。
4. 输入和输出按租户保留策略保存受控快照或指纹，密钥和模型私有思维链永不保存。
5. 下游结果由版本化 `OutcomeObserver` 扩展点接入，平台不能假设所有业务都以“人机一致”
   作为正确性标准。

## 8. 运行流程

### 8.1 影子和建议模式

```text
User Task 创建
→ 解析并固化 AutonomyPolicyVersion
→ 生成 taskActivationId 和 TaskDecisionEvidence
→ 通过 Outbox 创建 AgentRun
→ Agent 输出结构化 AgentProposal
→ SHADOW 仅进入证据；ADVISORY 展示给合法处理人
→ 人工完成任务
→ 保存 HumanDecision 和 EffectiveDecision
→ 后续 OutcomeObserver 持续补充业务结果
```

### 8.2 受监督模式

Agent 先填充结构化决定，人工必须查看并明确确认、修改或拒绝。前端按钮控制不能代替
后端完成前校验。最终 Flowable 操作者仍是人工用户。

### 8.3 受约束自动完成

Agent 只提交“建议完成命令”，不能直接完成任务。`workflow-engine` 在同一受控命令中：

1. 锁定并确认任务仍处于活跃状态。
2. 重新校验固化策略版本、任务风险、租户权限和输出 Schema。
3. 确认 AgentRun、Guardrail、Grounding 和必要证据满足门禁。
4. 使用 `taskActivationId` 和命令幂等键防止重复完成。
5. 以可审计的系统 Agent 身份完成任务并记录最终决定。

人工与自动完成并发时，第一个通过全部校验并提交的命令生效；另一个命令返回确定的
“任务已完成”结果并记录竞争证据，不重复推进流程。

## 9. 评测、升级与降级

### 9.1 指标

至少按租户、流程、Activity、AgentVersion、策略版本、风险等级和业务数据切片统计：

- 人机一致率、人工修改率、完全推翻率和建议采用率。
- 关键错误率、下游退回率、撤销率、投诉率和业务损失指标。
- Schema、Guardrail、Grounding 和引用证据通过率。
- 置信度校准、延迟、Token、成本和节省的人工处理时间。
- 新旧 AgentVersion 在同一历史样本上的差异。

全局平均值不能掩盖高风险或小样本切片。没有可用结果观察器的场景，应明确标记为
“仅有代理指标”，不能声称已证明业务正确性。

### 9.2 升级

第一阶段由系统生成升级建议，租户管理员或具备权限的流程治理人员审批后发布新的
`AutonomyPolicyVersion`。升级必须记录：

- 当前等级和目标等级。
- 样本区间、样本量和数据切片覆盖。
- 门禁指标及其置信区间。
- 严重错误和未决异常。
- 审批人、审批时间、灰度范围和回滚条件。

在没有足够样本、存在关键错误、评测数据漂移或证据版本不完整时禁止升级。

### 9.3 降级

平台必须支持人工立即冻结或降级。后续可以对严重 Guardrail 违规、质量回归、异常成本、
Provider 漂移和关键业务事件配置自动降级，但自动降级只能减少权限，不能自动增加权限。

降级后：

- 新激活任务使用新的策略版本。
- 尚未执行副作用的自动运行取消或转人工。
- 已完成任务不反向篡改，只进入调查、补偿或流程修复路径。
- 所有变化保留原策略版本、触发指标和操作者证据。

## 10. 历史回放

历史回放用于比较候选 AgentVersion、Prompt、模型、知识、工具或策略，不操作真实任务：

```text
历史 TaskDecisionEvidence
→ 恢复可用输入快照和版本引用
→ 使用候选版本生成新建议
→ Mock 或回放外部只读结果
→ 与人工决定、有效决定和下游结果比较
→ 形成 AutonomyEvaluationRun
```

回放必须默认禁止写工具和外部副作用。工具响应应使用测试 Mock、历史响应快照或明确的
只读沙箱。证据不完整、版本无法恢复或数据不允许再次处理的案例必须标记不可回放，不能
静默使用当前数据替代历史数据。

## 11. 与现有计划的关系

本方向不推翻现有 Agent Runtime。自主 Agent Service Task 可以按当前 MVP 计划独立完成；
它既不是自治等级提升的前置条件，也不能作为 Agent 已获得人工任务完成权的证明。进入
User Task 渐进式自治能力线后，内部按以下顺序推进：

1. 先关闭输出 Schema/结果策略、租约恢复、Provider 出站安全和 Worker 调度隔离问题。
2. 实现 User Task 的 `WorkflowAgentBinding`、`taskActivationId` 和策略版本固化。
3. 实现 `SHADOW` 和最小 `TaskDecisionEvidence`，不授予 Agent 完成权。
4. 实现 `ADVISORY`、人工反馈和任务界面建议。
5. 实现历史回放、版本比较、评测指标和人工审批的等级变更。
6. 实现 `SUPERVISED`，再实现低风险 `CONSTRAINED_AUTONOMOUS`。
7. 将运行证据接入多制品偏离诊断、受约束修复和流程生成闭环。

自主 Agent Service Task 仍用于不对应人工任务的自动分类、提取和分析。渐进式自治针对的是
“原本由人承担、未来可能由 Agent 部分或全部承担”的 User Task，两者共享 Agent Runtime，
但完成权语义不同。

## 12. 第一阶段验收边界

第一阶段只交付 `SHADOW` 和 `ADVISORY` 时，至少满足：

1. Agent 无法通过任何 Agent API 直接完成、驳回或转办 User Task。
2. 每次任务激活绑定确定的 AgentVersion 和 AutonomyPolicyVersion。
3. 人工先完成任务时，迟到 Agent 结果不会修改任务和流程变量。
4. 可以查询同一次任务的 Agent 建议、人工决定、最终决定和版本信息。
5. 人工接受、修改和拒绝建议均有结构化反馈且受租户权限约束。
6. 影子运行失败不阻塞人工任务，失败仍进入 Agent 运行审计和质量统计。
7. 日志、Trace 和接口不泄漏凭据、敏感 Prompt、未经授权的业务正文或模型私有思维链。
8. 多租户、重复事件、任务并发完成和 Agent 迟到结果具有集成测试。

达到上述边界只表示具备安全采集自治证据的能力，不表示 Agent 已获得生产任务完成权。
