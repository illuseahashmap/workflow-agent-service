# RAG 400 条合成评测候选集

**2026-11-28 交付方式更新：**按项目所有者要求，120 条原保留题已原字节复制到 `holdout/`，与 DEV/CALIBRATION 一并交付远端；不再是未曝光盲测。以下“仓库外保存”“保留集未访问”为历史记录。完整执行与回传以 [远端说明](../../../operations/remote-rag-evaluation.md) 为准；不要执行旧 build.py 重建覆盖本版本。

**最新：通用检索修复后复测。**题目、金标、语料和 K 不变，完整必要章节正文 Top 10 从 109/200 提升到 176/200；同时保留 3 条退步和时延增加，详见[根因修复与复测报告](../../../quality/rag-keyword-section-repair-20261127.md)。盲测集本轮未访问。

**2026-11-27 更新：**开发/校准题已复审至 manifest version 3；120 条保留题本轮未读取、未改动。开发集已完成项目真实原生关键词检索两轮（首轮发现评测前缀污染，修正后复跑），完整必要章节正文 Top 10 为 109/200，模型答案未评分。详见[复审与真实检索报告](../../../quality/rag400-project-rag-review-20261127.md)。以下初版生成记录、`validation-report.json` 和 `dev-lexical-*` 属于 v1 历史；最新公开题核验见 `public-validation-report.json`。

本轮已生成 **400 条 / 200 个场景组**，十领域各 40 条。每组两题分别检查规则理解和应用边界；二者同组同 split，不把它们当两个统计独立场景。

| 集合 | 题数 | 场景组 | 状态 |
| --- | ---: | ---: | --- |
| DEV | 200 | 100 | 开发回归，可查看金标 |
| CALIBRATION | 80 | 40 | 评分校准；不得用于检索/模型调参 |
| 保留集候选 | 120 | 60 | 工作区外保存；未独立复核、未认证盲测 |

总难度为 EASY 120 / MEDIUM 160 / HARD 120；可回答 280、缺证 60、范围外 20、禁止访问 20、冲突 20。包含多文档 80、跨领域 40、真正带跨文档依赖指向的多跳 40。详见 [分布](distribution.json) 和 [设计](../../../architecture/rag-400-case-benchmark-design.md)。

## 本轮完成了什么

1. 对旧十领域 40 篇材料做覆盖、草稿状态、重述与已曝光题族审查，生成逐文件哈希清单。结论与依据见 [原语料审查](original-corpus-audit.json)。没有将段落数当作独立知识点数，也没有宣称原材料绝对编不出 400 题。
2. 新增 30 份隔离命名的合成补充资料，340 个源章节；其中 10 份是受限合成附件。旧 40 篇及 pilots 未修改。**本轮冻结快照只索引这 30 份**，不把旧草稿与新沙盒规则混合成一套已生效制度。
3. 生成 400 条符合既有 v3 Schema 的候选用例，包含金标、定位、内容哈希、必需证据组、预期/禁止结论、权限范围、缺失事实和处置。多跳依赖使用单独 v1 附属格式，不篡改通用契约。
4. 所有用例完成作者自审与自动结构/引用校验。复核者字段保持空值，状态保持 DRAFT；没有捏造独立复核人或锁定记录。
5. 增加校验器、源锚点评分器及失败注入回归。开发集额外跑独立的本地词法诊断，保留集未跑质量评分。

## 文件用途

- `corpus/`：唯一允许摄取的正文。限制在专用合成测试租户，按快照 scope 配置权限。
- `knowledge-snapshot.json`：正文路径、文档版本、SHA-256、范围；当前 NOT_INDEXED。
- `auth-fixture.json`：声明的测试主体、允许范围和禁止范围，尚未部署。知识范围声明不能替代后端授权。
- `dev.cases.jsonl` / `calibration.cases.jsonl`：金标，只给评分器；**不可摄取、不可放入被测模型提示词**。
- `*.queries.jsonl`：不含金标的问题输入，caseId 只供评测关联，不作为模型推理提示。
- `*.dependencies.jsonl`：逐题源引用原文、依赖边及作者自审标记；不得作为检索库。
- `*.review.jsonl`：逐题作者自审记录，不是独立复核证明。
- `*.groups.json` / `*.manifest.json`：分组与摘要。manifest 中 `UNBUILT-DRAFT-ONLY` 是明确未建索引的占位标记，禁止当作有效索引 ID 发布。
- `holdout-commitment.json`：保留集数量和哈希，不包含保留集问题/答案。
- `validation-report.json`：全 400 条结构、引用和分组检查结果。
- `dev-lexical-*`：DEV 的本地词法诊断原始输出与汇总，不是项目真实检索器或真实模型成绩。

保留集与完整编题源文件存于本机 `C:/Users/email/Documents/Codex/RAG-Evaluation/rag400-v1-private/`，不进入当前开发仓库。该目录仍可被本机用户和本助手访问，**不是已经建立了访问控制的盲测保险库**。独立保管、复核及流程隔离完成前，只能称保留集候选。

## 审查中修复的问题

- 旧 POL/FAQ 和 CHANGE 重复解释同一规则：采用新的沙盒专题资料补足，旧候选发布说明不计独立场景。
- 源文件换行导致哈希跨平台漂移：本目录固定 UTF-8 / LF，并在源文件、manifest 与引用三处校验原始字节摘要。
- 否定问法偏多：补十条明确满足条件的肯定应用问题，不能一律回答“不可以”。
- 第一题显式给出章节主题可能让检索过易：去掉该提示；第二题为避免脱离场景保留必要主题上下文，报告承认该因素。
- 六个已曝光家族（采购报价附件、交付部分验收/范围争议、支持临时绕过/恢复关闭、销售交期预测）强制进入 DEV，同组不跨 split。
- 只有 MULTI_HOP 标签没有可追踪路径：补明确文档/章节指向，并校验依赖边有来源、可解析、无逆向循环。
- 共享权限条款在不同域重复且无法确定具体受限附件：补明确附件对象与域边界；仍共享通用权限/拒答机制，不声称未见模板泛化。
- 单条大段金标难以逐项检查：将主要回答和多证据依据分别记录为 claim，并绑定对应证据组。

## 已知能力与统计边界

这是合成小型资料上的基础能力诊断，不是生产分布或大型长文档压力基线。全部为当前时点、单轮问题；噪声、多轮、历史时点、PDF、多模态和真实 MCP 不在本版成绩范围。第二题常是同一事实的另一应用，置信区间和重采样以 200 个场景组为单位，而非将 400 条当完全独立样本。

“需要多文档”与“必须发起多次检索”不同：一次合法检索取得全部必要证据也能通过。跨领域只表示不同域证据共同支持，不自动标多跳。

qrels 当前只充分标记必要依据；不能把未标注候选全当负例，也不能直接宣称完整 Recall/nDCG。答案语义、合理替代证据、含义相似的跨组题仍需独立复核。自动结构通过不保证金标语义无误。共同的状态区分、权限拒答等推理模板仍跨集合，保留集不是未见推理模板实验。

权限题需实际后端按主体过滤正文、引用与回读；初版本地诊断只是按声明权限排除受限附件，不能代替授权验收。新增真实检索实测验证了 SQL 范围过滤，没有验证 HTTP 主体授权、引用回读或模型回答。不要把旧词法诊断成绩当现有产品质量分数。

## 复现与接入

仓库根目录执行（当前仅检查公开题，不读取保留集）：

```powershell
$private = 'C:/Users/email/Documents/Codex/RAG-Evaluation/rag400-v1-private'
$public = 'docs/fixtures/knowledge/rag400-v1'
node scripts/rag400/validate.mjs $public --public-only
node --test scripts/rag400/test-validator.mjs
python -B scripts/rag400/test_score.py
python scripts/rag400/diagnose.py --public $public --original docs/fixtures/knowledge/domains
```

以下为初版生成命令。当前公开题已复审，脚本会拒绝原地重建；不要执行本命令覆盖现有公开题或保留题。未来重新编题须另用版本目录并获得相应任务授权：

```powershell
python scripts/rag400/build.py --authoring "$private/authoring" --public $public --private $private
```

接项目真实 RAG 时，在隔离环境只摄取 snapshot 中正文，部署测试主体/范围，记录实际冻结 Profile 与索引 ID。将返回 Trace 的来源映射为 `documentCode/documentVersion/contentHash/sectionId`，保持排名，并输出 `caseId/status/snapshotSha256/evidence` JSONL。`status` 为 SUCCESS、ERROR 或 NOT_RUN；缺记录不能填成功。然后运行：

```powershell
python scripts/rag400/score-retrieval.py --cases "$public/dev.cases.jsonl" --snapshot "$public/knowledge-snapshot.json" --observations '<实际轨迹.jsonl>' --output '<本次评分.json>'
```

评分器拒绝重复 case、异快照；错误版本不能命中金标；无运行记录保留 NOT_RUN 且不从分母移除；禁止证据返回不能被其他命中抵消。它只测源锚点覆盖，不是服务器授权证明或模型回答评分器。后续需要真实检索适配与独立答案评分，不能拿手工轨迹代替。
