# RAG 评测用例与数据集契约

状态：设计稿（2026-10-17）；定义标注与离线评测格式，不代表评测运行器或发布门禁已实现
适用：十领域虚构企业知识语料；不得将草稿政策当作真实业务依据

## 1. 设计依据与取舍

检索、回答、业务处置是三个不同的被测对象，不用单一“答案正确率”替代。BEIR 与 TREC 的相关性判定支持按查询标注候选证据并独立评估排序；KILT 强调用固定知识快照和 provenance 同时约束答案与出处；RAGAS 将检索上下文、忠实度和回答质量分开；ARES 说明自动评委需要人工标注校准。CRAG 提醒加入动态、复杂和无答案场景。NIST AI RMF 的测试、评估、验证与治理思路要求留存数据、版本、方法和复核记录；OWASP 的 RAG 安全建议要求覆盖越权检索和间接提示注入。上述依据是本项目的设计参考，**下面的字段、分组和门禁是项目决策，不是论文规定的通用标准**。

原始资料：[BEIR](https://arxiv.org/abs/2104.08663)、[TREC relevance judgments](https://trec.nist.gov/howto.html)、[KILT](https://aclanthology.org/2021.naacl-main.200/)、[RAGAS](https://arxiv.org/abs/2309.15217)、[ARES](https://aclanthology.org/2024.naacl-long.20/)、[CRAG](https://proceedings.neurips.cc/paper_files/paper/2024/file/1435d2d0fca85a84d83ddcb754f58c29-Paper-Datasets_and_Benchmarks_Track.pdf)、[NIST AI RMF](https://airc.nist.gov/airmf-resources/airmf/5-sec-core/)、[OWASP RAG Security](https://cheatsheetseries.owasp.org/cheatsheets/RAG_Security_Cheat_Sheet.html)。

## 2. 版本和评测边界

`EvaluationDatasetVersion` 是不可变清单，至少锁定：`datasetId`、`version`、`status`、用例文件 SHA-256、知识快照清单（每个 `documentCode` 的内容哈希/版本/状态/生效区间）、索引版本或可重建配方、评分规则版本、标注规范版本、创建人、双人复核记录和冻结时间。`DRAFT` 语料可进入**沙盒评测**，但不得作为生产知识或正式发布门禁的权威答案。发布门禁只消费已审核的知识快照和已锁定的评测集版本。

运行记录 `EvaluationRun` 另行冻结被测的代码提交、`RetrievalProfileVersion`、Chunker/Embedding/Reranker/索引版本、模型与提示词版本、TopK、时间、随机参数、权限策略版本、租户/主体的**测试身份引用**、环境及失败摘要。用例不存真实凭据、生产 PII 或未经授权的原文；Trace 与评委输出按租户隔离并设保留期。数据集修改生成新版本，不原地改写历史分数。

固定的 `documentCode + documentVersion + sectionId + contentHash` 是标注锚点；`chunkId` 可记录为运行时映射，但不能作唯一金标，因为重切分会改变 Chunk。图片或表格目前仅能按其**正文中的文字等价说明**标注；图像像素、跨行表格语义不算现有能力。

## 3. 单条 `EvaluationCase` 契约

下表为逻辑字段；可先以 UTF-8 JSONL 保存，一行一例。[JSON Schema v1](schema/rag-evaluation-case.schema.json) 约束交换格式，[数据集清单 Schema](schema/rag-evaluation-dataset.schema.json) 约束版本元数据；字段间证据、时间、权限、人员分离和哈希一致性还需语义校验器，不能仅凭 Schema 通过就锁定。完整实施与当前能力缺口见[评测、历史快照与路由实施设计](rag-evaluation-and-routing-delivery-plan.md)。必填是指**用例冻结前**必填，不要求当前 40 篇草稿已经具备这些标注。

| 字段 | 约束与语义 |
| --- | --- |
| `caseId`、`schemaVersion` | 稳定、全局唯一；`schemaVersion` 首版为 `1`。语义修订派生新 case，不重用 ID。 |
| `supersedesCaseId`、`scenarioGroupId`、`domainCodes`、`split` | 修订用例显式关联前版；同一事实/模板/改写归同组；跨域只存一条主用例，可有多个领域；`split=DEV/CALIBRATION/LOCKED_TEST`，按**全局**组登记隔离。 |
| `query`、`queryTime`、`businessAsOfTime` | 原始用户问题、发问时间和业务事实适用时间，均带时区；关键时间缺失时 `businessAsOfTime=null` 且填写 `temporalGapReason`，不得默认用入库时间。多轮问题另带脱敏 `conversationContext` 和轮次。当前服务尚不支持历史 `asOfTime` 查询，此类用例不得进入正式自动评分。 |
| `tenantRef`、`principalRef`、`allowedKnowledgeScopes` | 测试身份与**期望可见范围**。运行时权限由系统重新计算，不能把样例里的 scope 当作授权指令。 |
| `scenarioType`、`riskLevel`、`difficultyTags` | 类型为 `ANSWERABLE/INSUFFICIENT/CONFLICT/OUT_OF_SCOPE/FORBIDDEN`；风险级别及可多选的阈值、跨期、多跳、否定、近义、注入等标签。 |
| `factsGiven`、`factsMissing` | 题目明确给出的可信业务事实及决定性缺项；业务事实若实际需外部系统确认，不能由文档检索臆造。 |
| `ruleTopics`、`applicabilityKeys`、`authorityDependencies` | 用于新增/替换权威文档时保守扫描潜在失效金标，不能仅扫描已经引用的证据哈希。 |
| `expectedDisposition` | `ANSWER/ABSTAIN/HUMAN_REVIEW`；附 `reasonCode`。`FORBIDDEN` 必须拒绝泄露，不能输出受限文档标题或片段。 |
| `relevanceJudgments` | 每个允许检索的候选证据有等级 `0/1/2`（无关/辅助/决定性），指向文档代码、不可变版本、章节或定位符、内容哈希；支持多份互为替代的证据及多文档组合。未标注候选为 **unjudged**，不自动视为 0。 |
| `forbiddenEvidence` | 记录测试身份不得看到的证据锚点，仅用于泄露断言；不得放入正常检索相关性分母。 |
| `requiredEvidenceGroups` | 对可回答题，AND 组中每组至少命中一项可替代的有效证据；例如采购规则 **与** 财务预算规则。无答案题可为空，但需写 `absenceOrConflictRationale`。 |
| `expectedClaims`、`forbiddenClaims` | 原子化可核对结论，每条含判定条件、允许表述、所需证据组；禁止作出的推断单列。`expectedAnswer` 只是供人工参考的可选范例，不作为唯一字符串金标。 |
| `citationExpectations` | 哪些结论必须有 Citation，引用需匹配同一租户、适用版本、章节与原文支持范围；不能只比对文档标题或模型生成的 URL。 |
| `annotation` | 题目编写人、独立标注人、复核人、分歧与裁决、标注时间、依据版本、状态 `DRAFT/REVIEWED/LOCKED`；同一人不能自写自审后锁定。 |
| `routeLabel`（可选） | 仅查询级策略路由评测使用：意图、可选/正确/禁止策略 ID 和理由；固定 Profile 的检索用例无需填写。 |

`relevanceJudgments` 允许记录**不可见的诱饵证据**，但须单独放在 `forbiddenEvidence`，不得与授权相关证据一起参与正常召回分母。`OUT_OF_SCOPE` 是文档不适用；`FORBIDDEN` 是可能存在文档但主体无权知道。两者在界面上可以同样拒答，在评测原因和安全断言上必须区分。

`annotation.status` 表示用例版本的标注/锁定状态，不承担知识变更后的时效复核职责。
已锁定用例及历史评测结果不可原地修改；时效复核记录应作为引用该 `caseId` 与
`EvaluationDatasetVersion` 的独立对象保存。

## 4. 示例（仅展示字段关系，不是已锁定金标）

```json
{
  "caseId": "PROC-BOUNDARY-0001",
  "schemaVersion": 1,
  "scenarioGroupId": "PROC-BOUNDARY-2000",
  "domainCodes": ["PROC"],
  "split": "DEV",
  "query": "普通办公采购含税正好 2,000 元，需要哪些角色审批？",
  "queryTime": "2026-10-17T10:00:00+08:00",
  "businessAsOfTime": "2026-10-17T10:00:00+08:00",
  "tenantRef": "synthetic-xinghe",
  "principalRef": "synthetic-employee-procurement-reader",
  "allowedKnowledgeScopes": ["knowledge/procurement"],
  "scenarioType": "ANSWERABLE",
  "riskLevel": "MEDIUM",
  "difficultyTags": ["EXACT_BOUNDARY", "ROLE_DISTINCTION"],
  "factsGiven": ["普通办公采购", "审批金额为人民币含税 2,000 元"],
  "factsMissing": [],
  "expectedDisposition": {"action": "ANSWER", "reasonCode": "EVIDENCE_SUFFICIENT"},
  "relevanceJudgments": [{"documentCode": "PROC-POL-01", "documentVersion": "0.2-draft", "sectionId": "§3.1", "contentHash": "<freeze-time-sha256>", "grade": 2}],
  "requiredEvidenceGroups": [["PROC-POL-01@0.2-draft#§3.1"]],
  "expectedClaims": [{"claim": "仅按采购金额档位，直属负责人审批必需；部门负责人不因该档位必需", "requiresGroup": 0}],
  "forbiddenClaims": ["采购审批通过即代表预算、合同或付款已经批准"],
  "citationExpectations": ["金额档位结论须引用 PROC-POL-01 §3.1 的有效授权版本"],
  "annotation": {"status": "DRAFT", "author": "<synthetic-or-reviewer-id>", "labeler": null, "reviewer": null, "adjudication": null}
}
```

**注意**：示例引用的是尚未批准的 `0.2-draft`，只能用于契约说明/沙盒；冻结时必须替换占位哈希并检查业务时间与文档生效区间。当前问题没有提供预算状态，因此不得推断可以下单。正式用例的 `expectedDisposition` 应按冻结快照重新标注。

## 5. 标注、抽样与泄漏控制

1. 文档负责人先审核事实、适用时间、权限矩阵和文档间权威关系；未通过的草稿只生成沙盒用例。
2. 编题人与证据标注人分离。标注人先独立阅读授权快照，记录全部决定性证据、可替代证据、缺失事实及应拒答原因；有分歧由领域复核人裁决并保留原标注。
3. 问题不得直接复制规则段落或把 FAQ 改写成“盲测”。每个领域覆盖精确边界、状态变化、时间跨期、同名实体、跨域组合、缺证/冲突、权限、注入和自然语言改写；按风险与难度分层报告，不用同义改写堆数量。
4. `scenarioGroupId` 在拆分前确定；相同事实、同一原始工单、同一模板和近义改写不得跨 `DEV/CALIBRATION/LOCKED_TEST`。锁定测试集对调参人员隐藏答案和评委理由；模型调用日志、FAQ 及提示词样例也做泄漏审查。
5. 首轮先每领域约 30 条人工复核试点，再扩展到每领域 100–200 条；这是**采样目标而非质量证明**。分层覆盖不足时补缺口，不能仅按数量宣称成熟。

### 5.1 知识变更后的金标时效复核（目标能力）

文档发布、替换、停用或有效期变更时，基于用例的
`documentCode + documentVersion + sectionId + contentHash`、知识快照清单及
`businessAsOfTime` 定位可能受影响的用例；**新增更高权威或跨域政策即使不改变旧锚点，也必须根据 ruleTopics、applicabilityKeys、authorityDependencies、时间和主体反查候选**；权限范围变更还须检查测试身份与预期可见范围。
依赖扫描只能产生“可能过时”的候选，不得据此自动改写标准答案或把旧证据一律判错。
同一事实允许关联多个可替代证据；仅当某个锚点变化时，应先判断其余证据是否仍支持结论。

受影响用例的**独立复核记录**进入 `NEEDS_REVIEW`，按 `caseId + 目标快照 + 触发指纹` 去重，保留触发事件、旧知识版本、
候选新版本、影响原因、发现时间与责任人。领域负责人和独立标注/复核人确认后，
只能选择：`STILL_VALID`（原金标仍适用于指定快照和时间点）、`REVISE`（派生新
`caseId` 与下一版数据集）、`RETIRE`（下一版数据集不再纳入该题）；裁决理由、
新旧引用及审批人必须留痕。历史锁定集、旧评分和旧版复核事实均不可覆盖。

数据集应区分长期稳定的安全/权限/核心回归用例与随业务知识演进的当前用例；
这与 `DEV/CALIBRATION/LOCKED_TEST` 的调参隔离是**两个独立维度**。新版评测集
保留两类用例，并在同一冻结数据集版本上比较新旧检索策略；跨版本报告单独列示，
不能通过移除失败题直接宣称质量提升。

## 6. 评分与门禁

| 层 | 指标及分母 | 解释 |
| --- | --- | --- |
| 检索 | `HitRate@K`、`Recall@K`、`MRR@K`、`nDCG@K`；仅在有授权正相关证据且判断充分的题上计算 | 保留 graded qrels；多文档题另报“全部必需证据组覆盖率”。未判定候选占比一并报告，不把它们直接算负例。 |
| 无答案/权限 | 正确拒答率、误答率、越权证据返回数、越权信息泄露数 | 缺证、冲突、越权分别报；跨租户或受限证据泄露是零容忍硬门禁。 |
| 回答 | 原子结论正确率、证据覆盖率、Citation 精确匹配率/有效率、无依据结论率、处置动作正确率 | 自动评委仅作辅助手段；高风险、拒答与模型/评委分歧样本人工复核。引用正确不等于结论正确。 |
| 运行 | P50/P95 延迟、成本、超时/失败率、重试恢复率、索引/权限版本漂移数 | 与质量按同一数据集版本、同一环境基线比较；不可把超时样本从分母悄悄删去。 |

每次报告保存**按领域/风险/难度/场景类型**的分层结果、原始计数、分母、未判定比例、失败用例 ID、评委版本与人工复核比例；小样本给出不确定性区间，不仅输出平均分。发布前和当前线上基线在同一锁定集上比较；具体百分比阈值待人工复核试点及真实基线形成后冻结，不能预设一个好看的数字。权限泄露、错误审批参与人、对高风险缺证题编造结论为硬阻断；其他阈值由风险负责人签发、变更需留痕。线上反馈和新增故障回灌下一版数据集，**不得修改已锁定版本或用测试集反复调参**。

`NEEDS_REVIEW` 用例不得静默计为正确、错误或从分母中消失。报告必须列出待复核数量、
风险分层与有效金标覆盖率；可另行输出“仅已复核用例”的诊断分数，但不得以此绕过
关键场景覆盖门禁。关键用例未复核完成或覆盖率不足时，评测运行可以保存诊断结果，
不能自动批准新 RetrievalProfile、路由权重或 Reranker 发布。

## 7. 与现有代码的衔接

现有 `EvidenceReference(sourceType, sourceId, sourceVersion, locator)` 可承载运行结果引用；用例金标需增加业务 `documentCode`、版本、章节和内容哈希，再由索引/Trace 将运行时 `sourceId`/Chunk 映射回固定正文。`RetrievalTrace` 保存本次实际授权、Profile/索引版本及候选结果，`EvaluationRun` 从 Trace 评分；不要为评测把租户或索引版本交给 Agent 自选。`GroundingPolicy` 当前仅有基础证据门禁，本契约不声称已支持逐结论 Citation 核验。

实现顺序：先冻结文档及权限测试身份 → 双人标注 30 条/领域 → 用固定检索快照复跑并补齐 qrels → 评委与人工一致性抽检 → 锁定测试集与基线 → 才接入自动发布门禁。流程 AgentRun/Flowable 另做端到端回归；RAG 答题分数不能替代确定性审批和组织关系正确性。
