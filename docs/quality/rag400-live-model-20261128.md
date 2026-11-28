# RAG400 开发集真实模型评测

日期：2026-11-28。源代码基线 `5297c52`。本轮针对公开 DEV 200 条，先执行项目真实检索，再调用用户指定网关的 `deepseek/deepseek-v4-flash` 生成答案并进行自动语义初评。未读取 120 条保留题；CALIBRATION 80 条仅随公开数据进行结构校验，未调用模型、未用于调参。

## 执行边界

本轮是 **项目真实关键词检索 + 真实模型答案的分层基线**，不是完整 Agent 执行验收。真实摄取、索引、SQL 范围过滤与关键词排序使用项目 Java 实现，在独立临时 PostgreSQL 17.5 数据库完成后删除。正文为冻结快照中的 30 份合成文档，没有向业务数据库导入题库。

回答阶段只接收问题和实际 Top 10 原文片段及定位，不接收金标、难度、场景分类、预期动作或 caseId。所有回答完成后，单独评分阶段才读取金标。模型自行组织答案；没有按金标补充正文、修改检索或修补回答。

本次不经过平台 AgentRun、LangGraph、MCP、Flowable，也没有调用模型选择工具、Query Expansion 或向量检索。SQL 使用 fixture 的固定授权范围，不代表 HTTP 主体授权、引用回读和业务流程去向已经验收。

## 运行参数

- 模型：`deepseek/deepseek-v4-flash`，回答与评分使用同一个模型、彼此独立请求。
- 并发：3；单请求超时 90 秒；输出预算 `max_tokens=1536`，包含供应方计入该上限的推理输出。
- 请求结构化 JSON 输出，应用侧检查字段、枚举和引用 ID；无自动重试、无输出修复、无失败样例换模型重跑。
- 固定检索 K=10，Keyword 模式；平台迁移 V1–V82，未安装 pgvector，未运行 vector 迁移。
- 每题最多一次回答和一次评分。回答失败则不调用评分，失败题仍留在总分母 200 中。
- 金标仍是作者自审的 DRAFT 合成候选。200 题属于 100 个场景组，不能视为 200 个统计独立场景；不提供伪精确置信区间。

## 评分口径

自动通过要求同时满足：回答完整且通过结构检查、引用 ID 来自实际返回片段且无重复、动作与金标一致、语义评分认为必要结论满足且无无据结论。ANSWER 必须有引用。

这不是字符串匹配，也不是“引用存在就算正确”。但同模型裁判可能有共同偏差，且金标本身尚未独立复核。自动未通过必须进一步区分：传输/预算失败、证据不齐、事实推理错误、过度拒答、金标或裁判过严。引用 ID 合法也不等于已逐项证明每条结论被其引用支持。

## 结果与原始数据

**本轮执行 200 条，不是全 400 条。** 本机仓库公开 DEV 200 条、CALIBRATION 80 条；文档记录的仓库外保留目录在本机不存在，holdout-commitment.json 只有 120 条的数量和哈希，不能替代题目。补齐全量需要取得并校验保留文件，且三组结果分别报告。

| 指标 | 本轮结果 |
| --- | ---: |
| 必要章节完整正文 Top 10 | 176/200（88.0%） |
| 完整且可解析的模型回答 | 192/200（96.0%） |
| 回答 HTTP 失败 | 1（503） |
| 回答预算耗尽、空正文 | 7 |
| 完成有效语义评分 | 170/200 |
| 评分预算耗尽、无有效评分 | 22 |
| 自动通过（分母不排除失败） | 137/200（68.5%） |
| 完成评分但未通过 | 33 |
| 未完成回答或有效评分 | 30 |
| 供应方返回的 Token 总量 | 817,298 |

共发起 200 次回答、192 次评分请求；8 条回答失败不调用裁判。Token 为输入 589,105、输出 228,193。7 条回答与 22 条评分的 finish_reason 均为 length；不能将这些预算失败说成事实推理失败。33 条有效评分未通过中又包含下述裁判争议，因此 **68.5% 是本轮配置下自动门禁通过率，不是人工确认的答案准确率**。

难度分组自动通过：EASY 50/60、MEDIUM 48/80、HARD 39/60。场景分组：可回答 104/140、缺证 7/30、范围外 10/10、禁止访问 9/10、冲突 7/10。各分组保留传输、预算和评分失败；权限场景成绩仍不能替代真实 HTTP 授权验收。

最终汇总以 [模型汇总](artifacts/rag400-live-20261128/model/summary.json)、[逐题结果](artifacts/rag400-live-20261128/model/results.json) 为准。

- [真实检索与覆盖汇总](artifacts/rag400-live-20261128/retrieval/summary.json)
- [逐题检索原文、定位和排序](artifacts/rag400-live-20261128/retrieval/project-raw.jsonl)
- [逐题模型回答、用量与错误](artifacts/rag400-live-20261128/model/answers.jsonl)
- [逐题语义初评与理由](artifacts/rag400-live-20261128/model/judgments.jsonl)
- [输入摘要、提示词和固定参数](artifacts/rag400-live-20261128/model/manifest.json)

不提交密钥、本机配置、包含本机账户路径的构建日志或原始 HTTP 头。Token 汇总来自成功返回的供应方 usage；失败请求的实际计费可能无法获知，不按未知价格推算金额。

## 复现

使用现有 `scripts/rag400/Rag400ProjectRetrievalTest.java` 模板复制到 workflow-boot 对应 knowledge 测试包，在独立测试数据库运行。通过 `WORKFLOW_TEST_DATABASE_URL/USER/PASSWORD` 提供凭据，禁止使用生产数据库。

```powershell
./mvnw.cmd -pl workflow-boot -am '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest=Rag400ProjectRetrievalTest' '-Dcheckstyle.skip=true' '-Drag400.migrationLocations=classpath:db/migration/platform' '-Drag400.input=<公开数据绝对路径>' '-Drag400.output=<新输出绝对路径>/retrieval' test
python scripts/rag400/normalize-project.py --public docs/fixtures/knowledge/rag400-v1 --run <新输出目录>/retrieval
python scripts/rag400/live-answers.py --config <仓库外私密配置> --public docs/fixtures/knowledge/rag400-v1 --retrieval <新输出目录>/retrieval/project-raw.jsonl --output <新输出目录>/model --workers 3
```

Python 使用已锁定的 agent-runtime-langgraph 环境（含 httpx）。私密配置字段为 baseUrl、model、apiKey，不能放入仓库。执行脚本会锁定输入/提示词/参数摘要，同输出目录禁止混入不同配置；再次运行只继续尚未记录的题，不重跑已记录失败。改变预算或提示词必须使用新的输出目录，不能覆盖本轮。

## 验证与下一步

### 已发现的评分争议

抽查自动初评时发现，部分金标把同节所有规则合并成一个 expectedClaim，而问题只问其中一项。裁判逐项照金标检查，会把正确但简洁的回答判为不完整。例如：

- `R400-ADM-e59e2543f7-1` 只问口头转让是否转移绑定，回答“不会，绑定车牌及办公地点”与原文一致；裁判却因为没有补充换车登记要求而判失败。
- `R400-ADM-eba06c67c3-2` 问取消会议后费用是否自动归零，裁判还要求包含场地预约释放规则，存在超出本问必要答案的疑点。
- `R400-ADM-6bc888946a-2` 的借用记录问题对应本轮 EMPTY 检索；模型拒答符合缺证保护，但未答出金标中的序列号，属于端到端任务未完成，不应简单记作模型幻觉。

上述是本次助手的定向复核观察，不是独立人工标注，也不是随机样本误差估计。没有据此改金标、修改评分提示词或覆盖失败。本轮自动通过率只能按当前规则解释；下一轮需把必要回答原子事实与可选补充说明分开，并先在校准集独立确认裁判尺度。

本轮检索集成测试 1 项通过，无失败、无跳过；输出契约单测 7 项、既有 validator 10 项、score 8 项、normalize 4 项通过。公开 280 条的结构与引用校验通过。不声称全部后端回归测试通过；本次检索模板运行跳过 Checkstyle，没有改产品检索逻辑。

下一步先复核失败分层与裁判争议，再固定独立评分样例和人工复核记录。后续如调整预算、模型、提示词或检索策略，必须建立新运行批次；本轮保留为原始基线。随后把同一 DEV 用例接入平台真实 Agent/LangGraph 入口，另验工具链路、授权和最终流程去向，不能由本报告替代。
