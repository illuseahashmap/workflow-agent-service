# 本机开发、远端评测与结果回传

更新：2026-11-28。唯一代码入口为后端 `codex/integration-hardening` 分支。本分支已合并远端 `5053914` 的原始评测及本机运行时修复，不能再用旧 `5297c52` 当当前产品基线。

## 本次修正

- 完整交付 DEV 200、CALIBRATION 80、原保留集 120，共 400 题。三组分别执行和汇总；保留原始字节及承诺哈希。原保留集已经进入仓库，不再宣称未曝光盲测。
- 检索、归一化、答案及评分入口统一支持 split，不再写死 DEV 200。
- 回答与评分分别配置输出预算，默认各 4096；供应方可能将推理 token 计入预算，仍需检查 finishReason。`length` 单独记录 BUDGET_EXHAUSTED，不计事实错误，也不移出总分母。无自动重试或自动改答案。
- 明确缺少所求结论必要事实时，即使给出解释，也须输出 ABSTAIN；不会在模型返回后偷偷把 ANSWER 改为 ABSTAIN。
- 保留严格 automaticPass，同时另列 semanticJudgePass、semanticPassActionMismatch、引用合法性及裁判字段。失败项输出 review-queue.json，不能把动作错误、金标争议与幻觉混算。
- 可用独立 judge-config 指定另一个评分模型；自动裁判仍不能替代独立人工复核。旧金标不因本轮分数而修改。金标要求过多的争议保留到独立复核队列，不自动放宽通过标准。
- 禁止混入缺失/重复/外来检索记录；续跑核对代码与输入哈希、检索原始字节及模型参数。配置改变必须新建批次；已记录失败不会在同批次重试。

原始 200 题结果保留在 docs/quality/artifacts/rag400-live-20261128。新结果不得覆盖它；旧 manifest 的 runnerSha256 对应旧提交的脚本，而非本次修改后的脚本。

## 环境准备

远端需 Git、JDK 25（JAVA_HOME）、Node.js 24.12+、Python 3.12–3.14、uv 0.12.6、运行中的 Linux Docker Engine/Desktop。Maven 使用 Wrapper；不需要全局 Maven、Redis、前端、日常业务数据库。模型运行需要可用 HTTPS 网关与调用额度。

```sh
git clone --branch codex/integration-hardening https://github.com/illuseahashmap/workflow-agent-service.git
cd workflow-agent-service
git pull --ff-only origin codex/integration-hardening
git rev-parse HEAD
git status --short
python -m pip install uv==0.12.6
uv sync --project agent-runtime-langgraph --frozen
npm ci --ignore-scripts --no-audit --no-fund
java -version
docker info
```

工作区必须干净。已经克隆其他分支的 AI 应新建工作区使用上述分支，不要强制重置已有工作。锁定依赖首次下载需要访问 Maven/npm/Python/容器镜像源。

## 先做离线验证

```sh
node scripts/rag400/validate.mjs docs/fixtures/knowledge/rag400-v1 docs/fixtures/knowledge/rag400-v1/holdout --no-write
node --test scripts/rag400/test-validator.mjs
uv run --project agent-runtime-langgraph python -m unittest discover -s scripts/rag400 -p 'test_*.py'
```

应报告 400 题、200 组、30 文档。这里只读取题库做校验，不调用模型；模型回答阶段只接收问题与实际检索正文，不接收金标、分类或预期动作。

## 第一步：三组真实检索

```sh
uv run --project agent-runtime-langgraph python scripts/rag400/run-remote.py --output ../rag400-run-001
```

输出目录必须是仓库外尚不存在的新目录。入口先验证完整数据；用随机密码、loopback 随机端口创建一次性 PostgreSQL，逐组运行真实摄取、V1–V85 平台迁移和关键词 SQL 检索，然后清理本次容器及卷。不会使用业务库。

检索测试模板临时复制到 workflow-boot 测试包，结束时移除；已存在时拒绝覆盖。模板仍沿用历史专项的 `-Dcheckstyle.skip=true`，此步骤不是完整后端回归或 Checkstyle 验收。其他真实应用集成测试保持独立。

## 第二步：真实模型回答与评分

在仓库及结果目录外创建仅远端自己可读的 JSON 配置，如 `../rag400-provider.json`：

```json
{"baseUrl":"https://YOUR-GATEWAY/v1","model":"YOUR-MODEL","apiKey":"YOUR-SECRET"}
```

不要把它放进 Git、输出包或回复中。可另建相同格式的评分模型配置，通过 `--judge-config ../rag400-judge.json` 使用。省略则回答与裁判使用同一模型，报告时必须说明。

```sh
uv run --project agent-runtime-langgraph python scripts/rag400/run-remote.py --output ../rag400-run-001 --resume --live --config ../rag400-provider.json --answer-max-tokens 4096 --judge-max-tokens 4096 --workers 3
```

也可首次即加 `--live --config ...` 一次完成检索与模型阶段。仅选某组用 `--splits dev` 或 `--splits calibration holdout-candidate`；续跑必须保持同一 splits 和顺序。默认完整 400 题最多发出 400 次回答、400 次评分，实际费用按供应方计；不自动调用真实模型。

如果有大量 BUDGET_EXHAUSTED，保留当前结果，另建目录并显式调整预算。不要删除失败行后续跑来抬高成绩。401/402/403/429 会停止后续发送（少量并发请求可能已发出），保留失败/未执行状态供检查。

普通中断后的续跑会跳过已经记录的回答和评分。若在检索输出尚未登记哈希前强制终止，入口会拒绝覆盖该部分目录；保留失败批次，新建批次重跑。若进程被强杀，按日志中的精确容器 ID 核对清理，检查临时 Java 文件；不要按通配符删除其他容器或代码。

## 回传哪些文件

回传整个 `rag400-run-001` 目录，打包时仅包含结果目录：

```sh
tar -czf ../rag400-run-001.tar.gz -C .. rag400-run-001
```

Windows 可用同样的 tar，或 `Compress-Archive -Path ../rag400-run-001 -DestinationPath ../rag400-run-001.zip`。

包内包含：

- run.json：源提交、三组题库摘要、检索原始字节摘要和执行范围。
- checksums.json：回传文件 SHA-256，可检测传输/换行修改。
- 每组 retrieval：实际 Top 10 正文、排序、检索评分和汇总。
- 每组 model：固定参数及输入摘要 manifest、原始 answers/judgments、逐题 results、summary、待复核 review-queue。

远端回复同时报告源提交、命令退出码、三组各自的分母/检索覆盖/自动通过/语义通过但动作不符/预算失败/HTTP 失败，并附压缩包。退出码 0 只代表流程执行完成，不代表每题正确；模型失败保留在数据和分母中。

不要把三组混成一个生产准确率。此入口仍是关键词检索加直接模型的分层基线；未验证完整 AgentRun、LangGraph、Flowable、真实主体授权、向量检索或查询扩展。CALIBRATION 用于独立校准，不根据它调整产品检索或提示词；对金标的任何复核修订另做数据版本和变更记录，不覆盖历史成绩。
