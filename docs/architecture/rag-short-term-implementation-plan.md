# RAG 短期实施方案与长期扩展边界

更新时间：2026-08-24
状态：下一阶段可开发规格

本文定义近期 RAG 能力的实现范围，并确保后续扩展混合检索、多跳检索、知识图谱、MCP
知识源和外部检索服务时，不需要重写 Agent Runtime、Flowable 集成和证据治理模型。

当前完成状态仍以[下一步计划](../status.md)为准，长期原则遵循
[开发方向与防偏离门禁](development-direction-guardrails.md)和
[Agent 协作架构设计](agent-collaboration-design.md)。

## 0. 交付范围与阶段零

本文是 RAG 的分阶段实施规格，不表示所有章节都属于同一个短期迭代。组织关系解析与知识检索
是两条并行但独立的产品能力线：

```text
组织关系解析
→ ParticipantPolicyResolver
→ 确定性参与人
→ Flowable 人工任务

知识检索
→ Evidence / Citation / RetrievalTrace
→ AgentToolRegistry
→ Agent Runtime
```

两条线可以共享租户上下文、版本、审计和证据引用能力，但不得共享一个模糊的检索编排器，
也不得让模型重新解释权威组织关系来决定审批人。组织关系后续应形成独立的
`organization-engine` 限界上下文；本方案只定义它与 Evidence 和参与人策略的协作边界。

在 RAG-1 之前必须完成阶段零：

- 确认知识源、文档版本和 Agent 的租户授权模型；
- 确认删除、禁用、撤权后的检索可见性和派生数据清理语义；
- 确认原文存储端口、单文档/租户容量、摄取并发和每日配额；
- 冻结 Embedding 模型、向量维度、检索最大耗时、最大返回字节数和索引兼容策略；
- 在真实 Retriever、权限求交、Trace 持久化和结果策略完成前，保持 `knowledge_search` 禁用；
- 明确 pgvector 不可用时的启动和降级策略，不得让部分基础设施能力静默伪装为可用。

## 1. 目标与价值

首版目标不是增加一个“向量搜索接口”，而是建立受治理的知识检索能力：

```text
流程上下文或用户问题
→ Agent 请求知识能力
→ 按租户、知识范围和版本执行检索
→ 返回可追溯证据与引用
→ Grounding 和结果策略判定
→ 自动推进、拒答或人工复核
```

RAG 在项目中的核心价值是为 Agent 结果提供可验证证据。MCP 负责受治理地访问外部系统和
工具，Flowable 负责长期流程；三者不能合并成一个不透明的“大模型调用”。

## 2. 评审结论

当前代码已经具备 `PLATFORM_AGENT`、`AgentToolRegistry`、租户工具授权、工具输入 Schema、
幂等键和执行审计，可以承载首个 `knowledge_search` 工具。现有长期设计也已经定义
`KnowledgeSource`、`RetrievalTrace` 和 `Citation` 等概念。

但如果首版直接把知识能力实现成 `searchChunks(query, topK)`，会形成以下长期锁定：

- Agent 和接口只能理解文本 Chunk，无法表达关系路径和结构化事实；
- 向量库类型、相似度分数和 Embedding 字段泄漏到领域契约；
- 图谱、多跳和 MCP 返回需要建立第二套结果模型；
- 历史运行无法还原当时使用的文档、索引、切分和模型版本；
- 召回成功与回答正确混为一个状态，无法建立质量门禁。

因此短期必须先固定中立的模块、证据和版本边界，再实现窄范围检索。

## 3. 模块与依赖方向

新增 Maven 模块 `knowledge-engine`，作为知识摄取、索引、检索、引用和评测的限界上下文：

```text
knowledge-engine
├── source          # 知识源、文档和版本
├── ingestion       # 解析、切分、Embedding、索引任务
├── retrieval       # 查询编排、Retriever、证据和引用
└── evaluation      # 评测集、评测运行和质量指标
```

依赖方向必须保持：

```text
knowledge-engine → shared-kernel
agent-engine     → shared-kernel
workflow-engine  → shared-kernel
workflow-boot    → 组合三个上下文并提供 AgentTool 适配器
```

`agent-engine` 不直接依赖 `knowledge-engine`，`knowledge-engine` 也不实现 Agent Runtime 接口。
`workflow-boot` 中的 `KnowledgeSearchAgentTool` 负责将 AgentTool 请求转换为知识应用服务请求。
这样未来替换知识实现不会改变 AgentRun 状态机，也不会让知识模块依赖 Flowable。

## 4. 稳定检索契约

### 4.1 RetrievalRequest

公开应用契约不得暴露向量、距离算法或数据库类型。至少支持：

```text
query                   原始问题
knowledgeScopes         已授权知识范围
filters                 受控元数据过滤
asOfTime                可选的时间点查询
maxResults              有界返回数量
strategyHint            AUTO / KEYWORD / VECTOR / HYBRID / GRAPH
maxHops                 多跳上限，首版只接受 1
requiredEvidenceTypes   调用方要求的证据类型
```

`tenantCode`、当前主体和数据权限来自可信服务端上下文，不能信任模型或客户端提交。模型提供的
`strategyHint` 只能作为建议，最终策略由版本化 RetrievalProfile 和平台策略决定。

### 4.2 RetrievalResult

返回值必须围绕证据建模，而不是固定为 `List<Chunk>`：

```text
RetrievalResult
├── resultStatus       SUCCESS / EMPTY / PARTIAL / REJECTED / FAILED
├── evidence[]
│   ├── ChunkEvidence
│   ├── RelationPathEvidence
│   ├── StructuredRecordEvidence
│   └── ExternalEvidence
├── citations[]
├── retrievalTraceId
├── qualitySummary
├── warnings
└── abstained
```

首版只生产 `ChunkEvidence`，但序列化契约、数据库 Trace 和 Agent Tool 输出必须允许增加其他
证据类型。禁止用无约束 `Map<String, Object>` 代替证据类型，也禁止让 Agent 依赖某个
Retriever 的专有返回。

### 4.3 EvidenceReference 与 Citation

引用不能只绑定 Chunk。统一引用至少包含：

```text
sourceType
sourceId
sourceVersion
locator
contentFingerprint
displayLabel
```

首版 `locator` 可以定位 `documentId/chunkId`；后续可以定位页码、表格单元格、结构化记录或
关系边。任何进入流程变量或自治证据的引用都必须能够验证租户权限和源版本仍然存在。

## 5. 领域模型与版本

### 5.1 首版必须实现

| 概念 | 职责 |
| --- | --- |
| `KnowledgeSource` | 租户内稳定知识源、类型和访问策略 |
| `Document` | 文档稳定业务身份 |
| `DocumentVersion` | 不可变内容版本、哈希、状态和存储引用 |
| `IngestionJob` | 可恢复的解析、切分、Embedding 和索引任务 |
| `Chunk` | 与文档版本绑定的规范化文本单元 |
| `IndexVersion` | 一次可切换、可回滚的完整索引版本 |
| `RetrievalProfileVersion` | 检索策略、TopK、融合和阈值的不可变版本 |
| `RetrievalTrace` | 一次检索的查询、过滤、命中、排序、耗时和版本证据 |
| `Citation` | Agent 结果到检索证据的稳定引用 |

### 5.2 首版只预留语义

以下概念暂不要求建表或提供管理界面，但稳定契约不得排除它们：

```text
EntityRef
EntityAlias
Relation
RelationEvidence
GraphIndexVersion
```

图谱需求出现后再根据真实查询和一致性边界落表，禁止第一阶段建设通用本体编辑器或引入
没有验收场景的图数据库。

### 5.3 必须记录的版本

一次检索至少关联：

```text
documentVersion
indexVersion
retrievalProfileVersion
chunkingStrategyVersion
embeddingModelVersion（向量检索时必填）
rerankerVersion（可空）
queryRewriteVersion（可空）
entityResolutionVersion（后续可空）
```

重新切分、重建索引和切换 Embedding 必须创建新版本，不能原地覆盖历史证据。

## 6. 摄取与索引可靠性

摄取任务必须落库，不能使用只存在于线程池内的任务：

```text
DocumentVersion 创建
→ IngestionJob QUEUED
→ Worker 使用 SKIP LOCKED 领取并获得租约
→ 解析与规范化
→ 切分
→ Embedding
→ 写入候选 IndexVersion
→ 校验完成后原子激活索引版本
```

要求：

- Worker 重启后可以依据租约恢复或重新执行；
- 每个阶段具有幂等键和确定状态，失败不会产生半激活索引；
- 文档删除、禁用和权限变化能够使旧结果立即不可检索；
- 原文通过内容存储端口保存，数据库只保存元数据、受控文本和内容引用；
- Parser、Chunker、Embedding Provider 和 Index Writer 都通过端口扩展；
- 文档大小、类型、页数、解析时间和 Chunk 数量必须有租户配额。

首版文件范围收敛为 UTF-8 文本和 Markdown。PDF、OCR、表格和图片在解析端口稳定后逐步接入，
不得因为文件类型扩展改变检索结果契约。

## 7. 首版检索实现

### 7.1 存储选择

首版采用 PostgreSQL 全文检索与 `pgvector`，理由是部署简单、事务和租户 RLS 可以复用。
领域模型和应用接口不得暴露 `tsvector`、向量维度、距离函数或 PostgreSQL 行 ID。

### 7.2 检索策略

首版实现：

- 关键词召回；
- 向量召回；
- 基于版本化权重的混合融合；
- 元数据和权限前置过滤；
- 有界 TopK 和内容长度；
- 零召回、低质量召回和部分召回的明确语义。

Rerank、查询改写和多查询召回先保留端口，只有离线评测证明收益后进入默认链路。

### 7.3 多跳与图谱兼容验收

首版不实现生产图检索，也不把组织关系查询并入 RAG。只提供测试用 `GraphRetriever` 或 Fake
Retriever，证明：

```text
RelationPathEvidence:
张三 --上级--> 李四 --上级--> 王五
```

可以经过同一 `RetrievalResult`、Citation、AgentTool 输出和运行审计返回，而无需修改
`PlatformAgentExecutor`、AgentRun、Flowable 或公开查询接口。

组织关系等权威结构化事实应由独立的 `OrganizationGraphQueryPort` 和参与人策略解析，优先使用
PostgreSQL 递归查询；只有事实主要来自非结构化文档、并且需要跨文档关系推理时，才考虑多跳
RAG 或图谱 Retriever。图检索结果不能直接授予审批参与人权限。

## 8. Agent Runtime 集成

首版注册只读工具 `knowledge_search`：

```text
PlatformAgentExecutor
→ AgentToolRegistry
→ KnowledgeSearchAgentTool
→ KnowledgeRetrievalService
→ RetrievalOrchestrator
→ Retriever Adapter
```

集成要求：

- 工具必须经过平台管理员注册、租户授权和 AgentVersion 选择；
- Agent 不能提交 tenantCode、索引版本或绕过权限的原始过滤表达式；
- 每次检索形成 `RETRIEVAL` Step 和 `RetrievalTrace`；
- 工具输出只包含受控证据摘要和引用，不注入无限原文；
- Checkpoint 保存恢复游标、逻辑步骤 ID、`RetrievalTrace` ID、受控输入上下文引用和
  `EvidenceReference`，不复制完整原文、完整模型响应或模型私有思维链；仅保存 Trace ID
  不足以支持恢复，不能将审计快照称为恢复点；
- 检索失败、零召回和证据不足不能伪装成普通回答；
- 必需知识步骤失败时，根据冻结策略执行重试、拒答、转人工或失败路由。

## 9. Grounding 与结果门禁

Agent 最终回答必须能够引用本次运行可访问的 `Citation`。首版 GroundingPolicy 至少支持：

- 是否强制引用；
- 允许的知识范围；
- 最少证据数量；
- 最低检索质量阈值；
- 关键输出字段是否必须有引用；
- 证据不足时 `ABSTAIN / FAIL / HUMAN_REVIEW`。

相似度分数不能直接等同于事实正确率，模型自报置信度也不能替代 Grounding。高风险任务即使
引用完整，仍必须经过业务规则和人工/自治策略门禁。

## 10. 评测与发布门禁

短期建立版本化最小评测集：

```text
EvaluationDataset
└── EvaluationCase
    ├── query
    ├── expectedEvidenceReferences
    ├── expectedAnswer（可选）
    ├── knowledgeScopes
    └── tags / riskLevel
```

召回指标至少包括：

- `Recall@K`、`HitRate@K`；
- `MRR@K`、`nDCG@K`；
- 零结果识别率；
- 权限过滤正确率；
- 后续多跳场景的实体链接准确率和路径准确率。

回答质量另行评估引用准确率、证据覆盖率、无证据回答率、结构化输出正确率和人工接受率。
索引或 RetrievalProfile 发布前必须与当前基线比较，不能只看线上单次相似度分数。

## 11. 安全与多租户边界

- 所有业务表强制租户过滤和 PostgreSQL RLS；
- 检索前完成权限过滤，禁止先跨范围召回再在内存中过滤；
- 文档原文、Chunk、Embedding、缓存、Trace 和评测样本全部属于租户数据；
- 检索结果进入 Prompt 前执行敏感字段过滤和 Prompt Injection 边界标记；
- 索引、对象存储和日志不得保存 Provider 凭据或模型私有思维链；
- 外部知识源和 MCP 连接器必须通过出站策略、凭据引用、超时和审计；
- 删除和保留策略必须覆盖原文、索引、缓存、Trace 和派生证据。

## 12. 分阶段任务

### RAG-0：治理前置与能力开关

- 固定知识源、文档、索引、Embedding、租户授权和删除传播边界；
- 固定容量、并发、超时、返回字节数和 pgvector capability check；
- 明确 `knowledge_search` 的启用条件和禁用时的可观测诊断；
- 建立最小评测数据集版本、阈值配置和发布决策模型。

完成标准：没有真实 Retriever、权限求交、Trace 持久化和故障策略时，工具不会被自动授权或
进入已发布 AgentVersion。

### RAG-1：模块与中立契约

- 新增 `knowledge-engine` 和 ArchUnit 依赖门禁；
- 实现 KnowledgeSource、DocumentVersion、IndexVersion 和 RetrievalProfileVersion；
- 固定 RetrievalRequest、RetrievalResult、Evidence、Citation 和 Retriever SPI；
- 提供 Fake Retriever 验证 Chunk、RelationPath 和 StructuredRecord 三类证据兼容。

完成标准：替换 Retriever 不修改 Agent Runtime、Flowable 和公开检索契约。

### RAG-2：可靠摄取与 PostgreSQL 检索

- 实现文本/Markdown 摄取、Chunk、Embedding 和索引版本；
- 实现 PostgreSQL 全文、pgvector 和混合检索；
- 实现摄取任务租约、重试、幂等、失败恢复和原子索引切换；
- 实现租户 RLS、配额和删除传播。

完成标准：服务重启、重复摄取和重建索引不会产生丢失、重复激活或跨租户结果。

### RAG-3：Agent、证据与质量闭环

- 注册并授权 `knowledge_search`；
- 写入 RETRIEVAL Step、RetrievalTrace 和 Citation；
- 实现最小 GroundingPolicy、拒答和人工复核路由；
- 实现版本化评测集、离线指标和发布回归测试。

完成标准：Agent 能使用引用回答问题；证据不足不会正常推进流程；运行详情可以还原检索版本、
命中证据和最终处置。

### RAG-4：真实场景与扩展验证

- 建立一个制度/手册问答和一个关系型多跳测试场景；
- 结构化权威关系通过 Tool/MCP 查询，文档关系通过 Fake/实验性 GraphRetriever 验证；
- 验证外部 Retriever Adapter 可以在不改变核心契约的情况下接入；
- 完成越权、Prompt Injection、错误引用、零召回和索引回滚测试。

完成标准：同一 Agent 可以消费不同证据类型，且权限、Citation、Grounding 和人工接管语义一致。

组织关系能力作为并行路线推进，不计入 RAG 完成判定：

```text
组织模型与有效期
→ OrganizationGraphQueryPort
→ 有限跳递归查询、环路检测和超时
→ ParticipantPolicyResolver
→ 任务激活时冻结参与人和关系路径
→ Flowable 创建人工任务
```

首版必须支持直属上级、指定层级上级和部门负责人，并对空结果、多人结果、停用人员和失效关系
执行显式策略；不得由模型决定参与人。

## 13. 明确暂不实现

- 通用知识图谱、本体编辑器和图数据库集群；
- 自动从所有文档抽取任意实体与关系；
- 完整知识库产品、网页爬虫和连接器市场；
- 多模态 OCR、音视频索引和复杂表格理解；
- 无评测依据的自动查询改写、Rerank 或多 Agent 检索；
- 让模型直接选择数据库、索引、租户范围或绕过 RetrievalProfile。

这些能力只有在真实案例和评测证明需要时才进入后续计划，但不得要求重写本方案定义的模块、
Evidence、Citation、版本和 Agent 集成边界。

## 14. 短期总验收

RAG 短期能力完成时必须同时满足：

1. 文档、切分、索引、Embedding、检索策略和引用全部可版本化追溯；
2. 摄取和索引任务在重启、重试和并发下可恢复且幂等；
3. Agent 只能访问当前租户和当前版本明确授权的知识范围；
4. 零召回、证据不足和检索失败具有确定状态，不会被包装成正常答案；
5. 回答可以定位到稳定 EvidenceReference，并通过 Citation 权限校验；
6. RetrievalTrace 能够还原查询、过滤、命中、排序、耗时和所有相关版本；
7. 离线评测能够比较索引和 RetrievalProfile 版本，防止质量静默回退；
8. 增加 GraphRetriever、StructuredRetriever 或外部 Retriever 时，不修改 AgentRun、Flowable
   恢复协议和公开检索契约。

### 14.1 `knowledge_search` 发布门禁

只有同时满足以下条件，才允许平台注册、租户授权和 AgentVersion 绑定 `knowledge_search`：

```text
真实 Retriever 已装配
+ 权限范围求交已生效
+ RetrievalTrace 可持久化
+ RETRIEVAL Step 和恢复游标已接入
+ 空结果、超时、低质量结果和越权测试通过
+ 固定评测集达到阈值
```

评测结果必须形成不可变的：

```text
EvaluationDatasetVersion
→ EvaluationRun
→ EvaluationCaseResult
→ QualityGateDecision
→ RetrievalProfileVersion 发布
```

`QualityGateDecision` 必须记录基线版本、阈值版本、失败样本和发布结论，不能只生成不可追溯的
报表。
