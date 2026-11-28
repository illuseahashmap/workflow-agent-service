# 远端 RAG400 四组对照执行说明

更新：2026-11-28。使用后端 `codex/integration-hardening`。本机维护代码和题库，远端执行付费模型评测并回传原始结果。本轮默认数据为 `rag400-v2`，不要继续用旧 v1 成绩直接计算提升率。

## 先读金标复核结果

[v2 题库说明](../fixtures/knowledge/rag400-v2/README.md)及 gold-review.json 记录修订：全部 400 题结构/引用校验；全部“关于 XXX：”模板前缀已移除，针对指代不清补全原有上下文；重点复核 60 道缺证题、过度要求和冲突前提。17 题经问题与原文核对后删去非本问必须的补充事实；29 道缺证边界问题允许有依据的否定解释或缺证拒答，仍必须满足参考语义、禁止无据批准。要求具体数值的题不放宽。原保留题去除评测指令前缀并统一固定权限声明。

这是作者复核，不是独立人工裁决；金标争议继续进入 review-queue，不能自动通过。原保留集已随仓库交付，不是未曝光盲测。旧 v1、旧 200 题结果及其哈希保持不变。

## 对照组与公平条件

| 模式 | 生产实现 |
| --- | --- |
| keyword | KeywordRetriever + PostgreSQL 关键词检索 |
| vector | VectorRetriever + pgvector；保留 StrategyRetriever 的结构化证据拒绝策略 |
| hybrid | StrategyRetriever：关键词与向量两路 RRF，k=60 |
| hybrid-expansion | 在 hybrid 上调用生产 QueryExpansionRetrieval / RRF_V1，原问题加一个模型生成的 topic-only 辅助查询 |

四组使用相同问题、正文、授权 scope、索引与最终 K=10。统计生产返回的最终证据列表，不能把被融合淘汰的候选算成命中。向量失败不能静默退回关键词。辅助查询失败、重复、条件越界、主路不适合融合等，按生产策略保留主路并明确记录 expansionTrace.outcome；报告真实 FUSED 比例，不能仅看开关就说全部用了扩展。

每个 split 在同一数据库与同一索引上跑四组。文档/原问题/辅助问题向量分别按文本哈希缓存，所有组复用同一模型/版本/维度；原问题向量不会因不同检索组再次生成。查询向量在计时前预热，所以 retrieval latency 是含缓存访问的生产 SQL/融合时间，不包含远程 Embedding；准备时间、新生成文本数在 project-run.json 中另列。Embedding 生产接口未暴露 Token 用量，费用需结合供应方账单，不能捏造；扩展、回答、评分保存供应方 usage。序贯单次运行不是生产 SLA 或显著性证明。

## 环境

Git、JDK 25（JAVA_HOME）、Node.js 24.12+、Python 3.12–3.14、uv 0.12.6、运行中的 Linux Docker Engine/Desktop。无需前端、Redis或业务库。

```sh
git clone --branch codex/integration-hardening https://github.com/illuseahashmap/workflow-agent-service.git
cd workflow-agent-service
git pull --ff-only origin codex/integration-hardening
git rev-parse HEAD
python -m pip install uv==0.12.6
uv sync --project agent-runtime-langgraph --frozen
npm ci --ignore-scripts --no-audit --no-fund
java -version
docker info
node scripts/rag400/validate.mjs docs/fixtures/knowledge/rag400-v2 docs/fixtures/knowledge/rag400-v2/holdout --no-write
uv run --project agent-runtime-langgraph python -m unittest discover -s scripts/rag400 -p 'test_*.py'
node --test scripts/rag400/test-validator.mjs
```

工作区必须干净，不要强制覆盖其他会话修改。Windows 的 Maven Wrapper 会自动处理；Linux 使用 bash。中途不要拉代码或改配置后强行续跑。

## 仓库外配置

以下配置只能保存在仓库及输出目录之外，不提交、打包或回显密钥。

`../embedding.json`：使用实际可用的 Embedding 模型，不是聊天模型。示例占位值必须替换：

```json
{"provider":"OPENAI_COMPATIBLE","baseUrl":"https://EMBEDDING-ENDPOINT/v1","apiKey":"SECRET","model":"EMBEDDING-MODEL","modelVersion":"PINNED-MODEL-REVISION","dimension":1024,"batchSize":16,"timeoutSeconds":90}
```

provider 可为 OPENAI_COMPATIBLE 或 VOLCENGINE_ARK，复用项目真实适配器。维度必须匹配模型（项目支持 1–4096）；Ark 可另配 queryInstruction/corpusInstruction，具体遵循该模型部署要求。模型版本、维度、指令在四组间保持一致。生产出站校验仍开启；遇到 Fake-IP/私网解析应修复网络，不关闭校验。

`../answer.json`、`../expansion.json`（以及可选 `../judge.json`）格式相同：

```json
{"baseUrl":"https://CHAT-ENDPOINT/v1","model":"CHAT-MODEL","apiKey":"SECRET"}
```

辅助查询只收到原始问题，不接收 caseId、金标、正文或检索结果；生产 topic-only 校验拒绝条件改写。回答只收到原问题、实际最终检索证据与检索拒答标记。评分阶段才读取金标、允许动作、参考答案与缺失事实。

## 正式执行

题库包含三组共 400 题。鉴于远端用户已明确停止原保留集，默认只运行 DEV 200 + CALIBRATION 80，共 280 题、四种模式；不会续跑旧保留集批次。先不加 --live 可以完成真实 Embedding、辅助查询和四路检索；这些准备步骤也会产生模型费用。加 --live 才进一步运行回答与评分。

```sh
uv run --project agent-runtime-langgraph python scripts/rag400/run-remote.py --output ../rag400-matrix-001 --embedding-config ../embedding.json --expansion-config ../expansion.json --live --config ../answer.json --judge-config ../judge.json
```

没有独立评分模型时可省略 --judge-config，报告会显示同模型评分的局限。回答/评分默认各 4096 Token，允许分别用 --answer-max-tokens、--judge-max-tokens 设置。4096 是待验证上限，不保证推理模型不会截断；失败留在分母中。

默认最多 1120 次回答、1120 次评分、280 次辅助查询，另加文档与查询 Embedding。可先用 `--splits dev` 验证，再另建批次做正式对照。仅在明确需要重新评测原保留集时，显式增加 `--splits dev calibration holdout-candidate`，此时最多 1600 次回答、1600 次评分、400 次辅助查询；三组分开报告，CALIBRATION 不用于产品参数调优。仅想复现单路基线用 `--modes keyword`，不要求 Embedding/扩展配置。

续跑使用同一个输出目录、相同 modes/splits/配置并加 `--resume`。入口校验源代码、题库、配置身份和原始产物哈希，跳过已记录请求，不重试失败来抬分。预算/模型/提示词改变必须建立新批次。部分检索在登记摘要前中断时，保留失败批次并新建目录；不能用未完整的一组和另一次运行拼成对照。

入口创建随机密码、loopback 随机端口的独立 pgvector 数据库，应用平台与向量迁移，结束删除容器和临时 Java 源码/编译文件。检索模板仍跳过 Checkstyle，不代表完整后端回归。强杀后按精确容器 ID 与日志核对清理，不使用通配符删除其他服务。

无密钥的接线验收可以使用 `--fixture-models`：生成明确标记的合成向量和辅助主题，不能与 --live 同用，答案脚本也拒绝把 fixture 结果当质量输入。此模式仅验证生产 pgvector/RRF/扩展代码接线，不提供模型质量成绩。

## 回传

打包整个输出目录，不包含任何配置文件：

```sh
tar -czf ../rag400-matrix-001.tar.gz -C .. rag400-matrix-001
```

输出结构：

- run.json：源提交、数据哈希、模式、非秘密配置身份。
- comparison.json：所选 split × 四模式分别汇总，明确 fixtureOnly。
- SPLIT/MODE/retrieval：最终 Top 10 正文及顺序、实际生产结果/扩展 trace、检索覆盖和时延。
- SPLIT/MODE/model：manifest、answers/judgments、results、summary、review-queue。
- SPLIT/expansion：query-only 辅助查询、失败/用量及固定输入摘要。
- embedding-cache.json：同批次复用的向量与非秘密模型身份。
- checksums.json：输出文件原字节 SHA-256。

远端返回源提交、完整命令与退出码、所选分组与模式的分母/覆盖/自动通过/语义通过但动作不符/金标争议/预算失败、FUSED 和回退数量，并附完整压缩包。退出码 0 是执行完成，不是每题正确。

这仍是生产检索组件加直接模型的分层对照，不是完整 AgentRun/LangGraph/Flowable/HTTP 授权端到端验收。相同题目成对比较，不混合 v1/v2，也不把合成题通过率说成真实业务正确概率。


## 已返回的关键词结果与原生数据库路径

远端提交 `3fb92eb` 的 [关键词基线](../quality/artifacts/rag400-run-001/SUMMARY.md)完整保留：DEV 156/200 自动通过、CALIBRATION 59/80；原保留集仅 41 条回答、无评分，用户中止，不是完整成绩。该批基于 v1；自然问法、金标、证据选择改变后的 v2 必须重跑 keyword，不与旧成绩直接宣称提升。

远端此前没有使用 Docker，而是原生 PostgreSQL 17.5。现在入口原生支持 `--database-config ../evaluation-db.json`，无需改评测脚本。示例：

```json
{"purpose":"ISOLATED_EVALUATION_SERVER","jdbcUrl":"jdbc:postgresql://127.0.0.1:15432/postgres","username":"postgres","password":"SECRET"}
```

仅允许显式声明的专用测试服务器、loopback、postgres 管理库；拒绝业务库 URL。账号须能创建并删除随机测试库、在其内创建 vector 扩展。入口只在新建 knowledge_/specimen_ 随机库中执行迁移与摄取，finally 删除本次数据库，不清理既有库。远端须先安装与 PostgreSQL 主版本匹配的 pgvector，`SELECT name FROM pg_available_extensions WHERE name='vector';` 应返回 vector；预检不通过就停止，不先花费扩展模型调用。不要为了测试重置已有服务器。

把上述 `--database-config` 加到正式执行命令即可。若进程被强杀，保留日志并核对本次数据库名称，不通过通配符删除。该选项不启动或停止原生 PostgreSQL 服务。
