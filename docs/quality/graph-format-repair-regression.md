# 最终答案有限格式修复与基础设施门禁

2026-11-26。对应 V78，业务后端未切换。

## 设计与兼容

仅新 v2 Run 可在已发布输出 Schema 中显式设置 `"x-graph-format":{"version":1}`，同时启用必要事实清单的 `allow_correction`。历史 Run、v1 wire 和未声明策略不变。格式策略在 reserve 时冻结，不接受图端覆盖；策略表强制 RLS，禁止改写/删除。

只对已具备必要事实的最终答案 JSON 解析/schema 失败授予一次修复。许可复用 `agent_graph_correction` 的 Run 主键，与循环纠偏/计划修订互斥；拒绝回执与许可原子提交。没有 Java 内部模型循环，也不修复计划草稿、工具动作、业务否决或缺失事实。

`FORMAT_REPAIR_REQUIRED` 通过 HTTP 映射为控制流；图显式进入 `prepare_format_repair`。稳定 operation ID 绑定 binding 和失败验证请求，原候选及原因由服务器回读。修复 MODEL 只生成新候选，不能派发工具/读取；新候选重新验证 Schema、当前权限与原始事实。修复仍失败即持久拒绝并沿现有无模型收尾路径结束，不另领机会。生成响应已保存时重放不重发；未知外呼仍隔离。

修复占既有 Run 模型、物理、逻辑预算，重新 VALIDATE 占本地预算。授予许可前检查剩余额度，执行时仍经过既有硬门禁。若修复需要额外摘要导致剩余预算耗尽，沿 Run 停止路径收尾，不能绕过上限。

## 验收范围

新增真实 PostgreSQL 回归覆盖：原子授予失败回滚、失去响应重放、一次许可、新候选验收、JSON/schema 两类错误、修复再次损坏、TOOL_CALL/TOOL_CALLS/READ_RESULT 拒绝、旧配置不启用、证据否决、模型预算不足、循环纠偏已用完许可、原始响应已保存后的换代恢复、缓存重放撤权。

Python 覆盖显式节点、稳定 ID、在授予/修复/最终验证响应窗口丢失后的 SQLite 冷恢复，以及 HTTP 非传输重试映射。模型为确定性替身，不等于真实 Provider 效果或完整 P-17 验收。

## 必跑与可选基础设施验收

Docker 下初次 `mvn verify` 成功，但严格基础设施门禁发现知识套件包含两个可选测试跳过。根因是必跑完整性测试与需显式开关的真实模型/大语料测试混在同一套件。

现将可选测试移至 `KnowledgeOptionalAcceptanceTest`，共享隔离数据库 fixture；未启用时整个可选类不启动数据库。必跑 `KnowledgeDocumentImmutabilityIntegrationTest` 保留 17 项，六套基础设施门禁仍严格要求零跳过，不修改检查脚本或降低标准。

可选复跑分别使用 `-Dworkflow.rag.benchmark=true` 或 `-Dworkflow.rag.live-specimen=true`，并选中 `KnowledgeOptionalAcceptanceTest` 的对应方法。本轮未启用付费真实模型或大语料性能验收。

## 本轮执行结果

- Python：94 项通过；全量入口中的 Java 联合调用首次因新增 schema 测试样本不符合预期而失败。样本由未约束字段改为顶层数组后，失败用例专项复跑通过，并继续完成当次被中断的宿主/Flowable 套件。未将首次全量入口记录伪写为 95/95。
- Java 图验收分段收口：首次 196 项中 195 通过、1 个测试样本错误；随后该用例和剩余 24 项通过，合计 220 个不同用例全部验收、零遗留失败/跳过。其中新增格式修复 11 项。实现代码未因样本修正而更改，不重复执行其余已通过用例。
- 图验收前清除旧 JaCoCo 数据；失败用例复跑与剩余套件续接本轮数据。全模块 Checkstyle 通过，四个关键治理类的 90% 行 / 70% 分支门槛通过。新 `JdbcGraphFormatStore` 实测行/分支均为 100%。Ruff 与差异空白检查通过。

| 类 | 行覆盖 | 分支覆盖 |
| --- | ---: | ---: |
| GraphCognitionGateway | 94.34% | 75.81% |
| JdbcGraphExecutionStore | 97.58% | 77.18% |
| JdbcGraphCallCapacityStore | 99.56% | 74.47% |
| JdbcGraphCompletionStore | 92.31% | 80.43% |

本轮图验证日志：`C:/Users/email/AppData/Local/Temp/workflow-format-full.log`、`C:/Users/email/AppData/Local/Temp/pytest-of-email/pytest-31/test_java_tool_gateway_postgre0/java-gateway.log`、`C:/Users/email/AppData/Local/Temp/graph-format-final-yle7nqu5/acceptance.log`。Docker 构建前已将图 Surefire 和覆盖报告存入 `C:/Users/email/AppData/Local/Temp/workflow-format-evidence-2_aq2l0s`，避免后续默认未启用图数据库的构建覆盖证据。

Docker Desktop 引擎 29.9.3 已启动。最终完整 `mvnw.cmd verify` 成功（4 分 1 秒），全模块 Checkstyle、SpotBugs、JaCoCo 基线通过。Surefire 报告 613 项：475 通过、138 跳过，零失败/错误。133 个跳过项属于本轮已单独验收的图数据库/Flowable 套件（类级禁用时参数化用例按方法计数）；其余 5 项为显式 P-17、原生 RAG/向量和新拆分的真实模型/大语料开关。不同运行的参数化计数口径不同，不能相加宣称唯一测试总量。

`node scripts/check-integration-test-results.mjs` 成功：六套必跑基础设施共 35 项，零失败、零错误、零跳过。分别为 Redis 锁 1、完成事件存储 3、Flowable 事务 3、认证 HTTP 2、平台迁移 9、知识不可变性 17。严格脚本未放宽。

Docker 日志：`C:/Users/email/AppData/Local/Temp/workflow-format-docker-verify.log`。测试结束后 `docker ps` 无运行容器，Docker 引擎保留运行。未迁移业务数据库、未切换发布版本、未调用付费真实模型。以上为本轮提交前的本地验收记录。
