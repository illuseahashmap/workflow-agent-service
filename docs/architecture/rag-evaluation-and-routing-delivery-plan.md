# RAG 评测、历史快照与查询级策略路由实施设计

状态：目标方案，非已实现能力；2026-10-17 评审基线。当前可运行边界以[项目状态](../status.md)为准。
主契约：[评测用例](rag-evaluation-case-contract.md)；语料：[十领域设计](rag-ten-domain-corpus-design.md)；运行边界：[RAG 短期计划](rag-short-term-implementation-plan.md)。

## 1. 决策和非目标

优先建立固定检索 Profile 的**可信评测基线**，然后做历史时间检索和自动发布门禁；加权 RRF、Reranker 与查询级路由只有基线表明收益且前置安全条件满足后才实施。动态路由只从 AgentVersion 明确绑定的不可变策略快照选择，绝不产生临时权重、模型、租户、知识范围或索引版本。Agent 只得到已授权检索结果；文档不能决定真实审批参与人。

本方案不在本轮实现多模态解析、通用策略市场、任意历史数据库“时间旅行”、在线自动重标或无审核发布。现有 `RetrievalRequest.asOfTime` 会被应用服务拒绝；现有 `RetrievalProfileVersion` 只绑定单个策略/索引，`RetrievalTrace` 还不足以做逐结论 Citation 评分。以下状态机与存储是**目标设计**，不可作为现状宣传。

## 2. 模型与权威边界

```text
业务负责人批准 DocumentVersion（生效区间与替代关系）
 → 不可变 KnowledgeSnapshot/IndexVersion
 → 标注人按快照制作 EvaluationCase + qrels
 → 独立复核并锁定 EvaluationDatasetVersion
 → 候选/基线在同一快照、身份和环境下回放
 → EvaluationRun + CaseResult + QualityGateDecision
 → 人工授权发布 RetrievalProfileVersion / AgentVersion

知识/权限变更 → 影响分析 → CaseReview（独立事件）
 → STILL_VALID / REVISE / RETIRE → 下一版数据集
```

`KnowledgeSnapshot` 至少含租户、文档版本与哈希、状态、适用对象、生效起止、替代/优先级、授权策略版本、索引版本和嵌入模型版本。比较两种检索策略时**同一**快照、权限、问题、预算和 TopK；需要测试新索引时将“新旧索引差异”作为实验变量，并另行报告数据差异，不把不同知识内容伪装成纯算法对比。历史锁定集按原快照复现；当前集按新快照重标。若历史版本已因合规删除不可读取，应标记 `NOT_REPLAYABLE` 并解释，不得从当前文档臆造旧证据。

在索引构建时建立 `EvidenceLocator` 映射：`documentCode/version/sectionId/sectionHash` ↔ `sourceId/sourceVersion/locator/chunkId`。映射随索引版本冻结；评测时校验同租户、同快照、同有效期。Chunk ID 不是业务金标。禁止把 `EvaluationCase.allowedKnowledgeScopes` 当成运行授权，评测请求必须由可信测试身份经现有 `KnowledgeAccessPolicy` 重新求交。

## 3. 用例和数据集生命周期

设计期以 [case JSON Schema](schema/rag-evaluation-case.schema.json) 和 [dataset manifest JSON Schema](schema/rag-evaluation-dataset.schema.json) 约束可交换格式。Schema 负责类型、枚举和条件必填；冻结前的**语义校验**还必须验证证据锚点在快照中存在、哈希/时间/主体/权限一致、`requiredEvidenceGroups` 全部指向允许的 grade 2 或经审核的等价证据、claim 引用的组号有效、跨文档冲突已裁决、同一 `scenarioGroupId` 不跨 split、编题/标注/复核人分离、难度画像可派生字段及等级符合 manifest 冻结的 `difficultyRubricVersion`、数据集哈希与文件一致。JSON Schema 不能代替这些数据库/业务校验。

`DRAFT → REVIEWED → LOCKED` 是用例版本状态；变更产生新 `caseId` 与 `supersedesCaseId`，保留稳定 `scenarioGroupId`。`CaseReview` 是独立对象，不覆盖锁定用例：`NEEDS_REVIEW → STILL_VALID/REVISE/RETIRE`，对同一 `caseId + targetSnapshotId + triggerFingerprint` 唯一，以幂等处理重复文档事件。每次裁决保存触发事件、旧/新快照、影响理由、候选证据、复核人和时间。`STILL_VALID` 仅对明确的目标快照与时间区间有效；`REVISE` 进入下一数据集版本；`RETIRE` 必须给出原因和覆盖替代方案。旧集仍可按旧快照复跑，不能原地删题或改分。

数据集 manifest 的 `DRAFT/LOCKED` 是文件创建时状态；已锁定版本退役只写独立登记事件，不将旧 manifest 改成 `RETIRED`。JSON Schema 实现必须开启 `format` 日期时间断言、Draft 2020-12 条件分支校验，并以样例验证 `LOCKED` 的双人复核和哈希，不能仅确认 JSON 可解析。

拆分分配按全局 `scenarioGroupId` 稳定哈希/登记册执行，不只在单一数据集内检查。相同业务事实、模板、工单、FAQ 衍生题和近义改写都进同组。开发、校准与锁定测试集隔离；基线报告记录样本数、未标注候选数、标签覆盖、置信区间及人工复核比例。用例按 `retrievalScope/reasoningType/queryForm/temporalDemand` 形成结构化难度画像，`EASY/MEDIUM/HARD` 只由版本化规则派生；抽样和报告同时展示各维度分布，不能用大量单章节直接题抬高总体指标。持续使用锁定集反复调参会使其失去独立性，必须定期新增经审核的真正盲测集。

## 4. 金标失效影响分析

不能只扫描用例**已引用的** `documentCode + version + sectionHash`。以下变更均触发候选检查：新发布/替换/撤销同主题的更高权威文档；适用对象或有效期变化；相邻政策新增例外、阈值、定义或否定条件；索引可见性、租户/主体权限变化；权威关系、组织事实或数据来源版本变化；标注规范变更。反例是“旧采购 §3.1 原文未变，但新生效财务政策限制了某类支付”——跨域答案仍可能改变。

为此用例除证据锚点，还记录 `ruleTopics`、`applicabilityKeys`、`domainCodes`、`businessAsOfTime`、`requiredEvidenceGroups`、测试身份和 `authorityDependencies`；文档发布事件带相同维度。先做精确引用和主题/范围/时间的保守候选扫描，再由规则/人工确认；低置信度时**扩大待复核集合**，不自动判仍有效。新增文档如果可能覆盖旧答案，不能因为原金标哈希不变而跳过。事件去重、游标/租约与重试保证扫描最终完成；扫描失败阻断受影响范围的自动发布，保留审计和人工处理入口。

评测报告分别输出原锁定集的历史可重放率、新快照下有效金标覆盖率、待复核数和分层诊断成绩。`NEEDS_REVIEW` 不计入通过/失败分母，也不能无声剔除：关键用例待复核或覆盖低于已批准阈值时 `QualityGateDecision=BLOCKED`。跨版本分数必须标明样本和知识是否变化，不能宣称同质提升。

## 5. 历史检索与有效时间

`queryTime` 是问句提出时间，`businessAsOfTime` 是要回答的业务事实时间，`knowledgeRecordedAt` 是平台知道该文档的时间，`effectiveFrom/To` 是制度适用区间；四者不能互换。当前 `asOfTime` 未实现，因此含历史时间或跨期金标的用例只允许在设计/标注层保存，自动执行结果为 `UNSUPPORTED_HISTORICAL_QUERY`，不得计入正式质量门禁。

实现时先由已发布 AgentVersion 和可信服务上下文解析租户、Profile 与允许的知识范围，再选**明确授权且在该业务时点适用**的不可变文档/索引版本。历史访问还受当前主体对历史资料的权限和保留策略约束，不因曾经有权而自动获权；检索语句在数据库侧进行租户、scope、有效时间和可见状态过滤。当前索引与历史快照可分开存储，但必须有稳定映射；已删除或权限收回的资料不能从旧向量、缓存、Trace 或评测副本泄露。冲突、时间不明、适用主体不明时拒答或转人工，不用“最新”默认为业务时间。

验收：生效前/当日/失效后、晚发布但追溯生效、同级冲突、撤权、跨租户、删除传播、缺少历史快照和 AgentRun 重启恢复。若监管或保留要求禁止保存旧原文，则记录不可重放原因并把该题从**新**快照正式基线中审议移出，历史报告保持不变。

## 6. 查询级路由与安全回退

仅在固定 `KEYWORD/VECTOR/HYBRID` Profile 的检索基线和门禁建立后推进。发布时冻结 `RetrievalStrategySetVersion`：租户、策略 ID → 不可变 `RetrievalProfileVersion`/IndexVersion/能力（输入模态、支持的证据类型、最大时效、风险上限、预算）、默认/失败动作、路由器规则或模型版本、Schema/指纹。AgentVersion 绑定该集合版本；运行开始快照候选集合，重试/恢复不重新发现新策略。策略下线、新旧索引不能匹配或权限收缩时拒绝调用或执行有据可查的人工/失败策略；不得自动拓宽范围。

顺序为：可信身份/能力与知识范围求交 → 从冻结集合中筛出**当前仍被授权且可满足查询的候选** → 有界路由输出策略 ID（可带置信度/理由码） → 平台再次验证版本、模态、数据新鲜度、风险、预算、IndexVersion 和授权 → 调用检索 → Trace 记录候选、选择、拒选/回退原因、实际版本与耗时。路由模型不得看到未授权策略的名称或受限数据，不能输出 SQL、权重或新的 endpoint。

路由失败时默认策略必须对该请求**能力等价且不降低授权/风险约束**；例如图片专用问题不能无声回退纯文本，精确条款问题不能因模型超时直接改成无法保证精确性的路径。无等价策略时返回 `UNSUPPORTED_MODALITY/NO_ELIGIBLE_STRATEGY/ROUTER_UNAVAILABLE`，由冻结失败策略澄清、拒答或转人工。安全回退不等于“找到任意答案”。路由前后均执行最大时延和成本预算；旧 Attempt 恢复时复用已持久化的选择或按相同冻结快照重放，不因新版本发布漂移。

先用规则路由做可解释基线，再比较小模型/分类器；评测集加 `queryIntent`、`eligibleStrategyIds`、`expectedRouteIds`（可多解）、`forbiddenRouteIds`、`routeReason`，但这些属于**路由标注扩展**，不强塞给只测固定检索器的基础 `EvaluationCase`。报告路由误选、错误回退、无等价策略正确拒选、各模态/风险分层质量、延迟和成本，并与“始终固定 HYBRID”比较。图片策略在多模态摄取、引用、授权和评测前不得发布。

## 7. 存储、接口与运维（目标能力）

候选持久对象：`knowledge_snapshot`、`evaluation_dataset_version`、`evaluation_case_version`、`evaluation_case_review`、`evaluation_run`、`evaluation_case_result`、`quality_gate_decision`、`retrieval_strategy_set_version`。所有租户数据表带可信 `tenant_code`、RLS、唯一版本/指纹、创建/批准时间；锁定记录不可更新，只允许追加。大原文与模型输出保存在受控对象存储，数据库存哈希、大小、租户和稳定引用；删除传播、保留期、脱敏与访问审计覆盖评测和 Trace。不要让可编辑文档仓库成为生产运行的唯一权威库。

应用端口按限界上下文分离：`KnowledgeSnapshotPort`、`CaseReviewPort`、`EvaluationRunnerPort`、`QualityGatePort`、`RetrievalStrategyCatalogPort`。第一版可采用受权限保护的管理命令/API 导入、锁定、回放和审查，不必先建完整 GUI。评测 Worker 通过持久队列/租约领取任务；幂等键由 run、case、候选配置与知识快照确定；超时和取消有终态，失败不得当低分或成功。跨租户容量、公平配额、外部评委/Embedding 成本上限、重试和恢复纳入监控。文档变更事件是唤醒信号，周期性全量对账修复丢事件；频繁上传可合并候选事件，但不能跳过已发布版本的影响扫描。

`QualityGateDecision` 保存候选和基线运行 ID、数据集/快照版本、门禁规则版本、原始分子分母、置信区间、硬阻断项、待复核覆盖、成本/延迟与裁决人。决策分 `PASS/BLOCKED/REQUIRES_HUMAN_REVIEW/INVALID_RUN`；任何可重放性、权限泄露、关键用例时效或快照一致性问题阻断自动批准。正式阈值由领域/风险负责人依据试点数据签署版本，不在设计文档拍脑袋设值。

## 8. 阶段、验收与失败回滚

| 阶段 | 交付与验收 | 依赖 |
| --- | --- | --- |
| E0 契约与试点 | 机器 Schema、语义校验器、版本化难度规则、30 条/领域双人标注沙盒用例；字段、引用、分组去重、难度分层一致性和禁止泄漏测试通过 | 40 篇草稿仅作沙盒，不能称生产基线 |
| E1 固定快照基线 | 文档审核/发布、不可变快照与授权身份、qrels、KEYWORD/VECTOR/HYBRID 同条件回放、CaseResult/Run 报告 | 容器化租户/RLS/索引回归 |
| E2 时效与发布 | 历史时间查询或明确不可执行状态、变更影响扫描/CaseReview、门禁与人工批准、故障恢复 | E1 且关键场景有金标覆盖 |
| E3 策略增强 | 加权 RRF、Rerank、固定策略集合与路由器分批验证；先 shadow 再灰度 | E2、严格能力等价回退、模态能力落地 |

每阶段都回归：授权与跨租户、阈值、缺证/冲突、同义/精确、时效、删除传播、Trace/恢复等价。E3 失败可将新 AgentVersion/StrategySet 停用并回滚到上一已发布组合；**不修改**旧快照、旧数据集、旧评测或旧 Profile。生产发布需人工看差异和阻断原因。未完成 E0–E2 时，评测只提供实验诊断，不宣称生产级 RAG 门禁。

## 9. 设计复核记录与未决决策

本方案已按以下边界自查：固定 Profile 先于动态路由；业务金标不依赖 Chunk ID；跨期问题不被当前检索实现假装支持；新增高权威文档也触发失效扫描；审查事件不改锁定数据；不等价的路由回退不会伪装正常结果；评测发布不绕过租户权限。对应的代码与容器化验收**均尚未完成**。

实施前需要由领域与运行负责人明确三项配置，而不是由评测设计者猜测：①哪些关键场景及租户必须达到多少有效金标覆盖；②各风险层的质量、P95 延迟和成本阈值，以及人工审批人；③历史资料的保留/删除策略和不可重放时的合规处置。阈值须在试点与基线数据出来后版本化批准。若没有这些决定，`QualityGateDecision` 只能是 `REQUIRES_HUMAN_REVIEW` 或 `BLOCKED`，不能自动通过。
