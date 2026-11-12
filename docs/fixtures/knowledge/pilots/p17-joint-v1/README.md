# P-17 下单核验：联合验收 E0（DEV）

这是可离线重放的验收夹具，不是下单服务，也不是模型准确率报告。场景问的是“能否”，未授权提交订单或扣款。B08 保持原题；七个变体同属一个 DEV 分组，不拆分成训练题和盲测题。

| 制度与实时回执 | 预期处置 | 唯一活动人工任务 |
| --- | --- | --- |
| B08 只有政策、未提供实时工具 | 证据不足，不编造记录 | NEED_RECORDS |
| P-17 的审批、预算、报价有效期、准入、签署权限均满足 | 条件满足，仍需人工确认 | READY_CHECK |
| 五项实查，预算明确不足 | 条件不满足，不是“查不到” | NEED_RECORDS |
| P-17 查空 / 正确查询 P-17 却收到 P-18 | 分别测试；没有该单有效依据 | NEED_RECORDS |
| 两次读取均超时，预算耗尽 | 转人工 | MANUAL_REVIEW |
| 成功取证后撤权 | 不再引用受限内容，转人工 | MANUAL_REVIEW |

- [用例契约](cases-v1.schema.json)与[金标](cases.json)冻结主体、工具等价名、重试预算、观察窗口、原件指纹及预期结果。政策证据使用[原采购试点](../procurement-approval-v1/README.md)的 `order` 锚点，不复制或修改 B08。[只读夹具](receipts.json)提供该单与干扰单的事实，不写入业务系统。
- [评分输入 Schema](trace-v1.schema.json)与[评分器](../../../../../scripts/p17-evaluation.mjs)将同一次实验分成检索、工具、结果与流程三项。时间顺序字段 `at/revokedAt/enteredAt` 是同一试验统一的事件序号，`observedMs/completedMs` 是同一时间基准的毫秒数。每段须携带同一 trial、case、租户、主体、Run、最终 Attempt 和流程实例；调用另带实际 Attempt、步骤 ID、提议能力/参数、策略决定、执行或复用方式、回执。恢复的引用必须指向本运行的原始执行并重新授权。迟到且已丢弃的结果不算依据。
- 检索记录中的 `readBack` 表示取证当时授权回读过的源锚点；`answer.citations` 和 `restrictedDisclosures` 来自最终输出检查。工具的 `used` 表示本 Attempt 在作出决定前观察了结果（包括空值/异单），不表示该结果能证明条件满足。能力被拒但未执行与实际执行分开记录。副作用从独立台账取，不从模型自述取。缺日志、跨单次实验拼接、观察不足均不能通过。若出现缺证放行、泄露或真实写入，单列 BLOCKED。
- [测试 BPMN](order-readiness.bpmn20.xml)只有一个 Agent 等待节点、一个排他网关、三个只读核验后的人工去向，没有提交/扣款节点。默认去人工复核。`agentVersionId="1"` 是隔离夹具占位，**不能原样部署到业务租户**。E1 须替换为核验过的已发布测试版本 ID，记录替换后 BPMN、版本和快照。

**复跑（仓库根目录）：**

```powershell
npm run test:rag-contract
$pilot = 'docs/fixtures/knowledge/pilots/p17-joint-v1'
node scripts/p17-evaluation.mjs "$pilot/example.manifest.json" "$pilot/example.trace.json"
.\mvnw.cmd -pl workflow-boot -am '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest=P17RoutingFixtureTest' test
```

前两条不调用模型或数据库；示例文件是手工轨迹，不用于评价模型。CLI 对非 PASS 返回非零。Java 测试使用 Docker 或显式配置的隔离 PostgreSQL（`WORKFLOW_TEST_DATABASE_URL/USER/PASSWORD`，测试账号须能创建临时库）。2026-11-10 已在 PostgreSQL 18.1 实跑，两个测试均未跳过：一项校验平台 Agent 节点绑定，另一项在实际 Flowable 上触发等待节点并查活动任务和历史，覆盖正常、预算不足、缺资料、转人工、失败时残留肯定结论、输出矛盾和空输出。

### E1 实验入口：真实工作流，独立标记模型来源

`P17JointRunTest` 在临时数据库内迁移、摄取采购 Markdown、发布 KEYWORD Profile 并给 buyer 授权。B08 的已发布版本只含知识检索，另一已发布版本还绑定审核过的订单 MCP 快照；二者均通过测试 BPMN 的实际监听器进入 Worker。MCP 的连接目录、版本绑定、工具权限与审计是真实代码，只有远端订单系统由进程内只读夹具替代，不测试 HTTPS 传输。不会改动业务租户。

```powershell
# 从仓库根目录运行；只选脚本模型，任一用例未通过则非零退出
.\scripts\test-p17-local.ps1
```

确定性驱动覆盖全部七个变体，核对同一个 Run 的工具提交、完成事件、活动任务和历史节点。超时通过两次真实 Worker 领取耗尽预算（测试退避为零）；撤权在知识工具成功并授权回读后、结果返回执行器前实际删除授权，检查后续无模型请求、无最终答案、原文不可再读且流程转人工。失败转人工来自平台，不是模型给了正确答案。报告分次写入 `workflow-boot/target/p17-runs/<批次>/<用例>.json`，包含真实实例与 Run ID、快照、步骤、检查点标识、只读调用和最终任务；不保存凭证。该原始报告**不是**可直接送入 E0 评分器的完整轨迹。

**同 Run 判分。** 上述入口为每批新建 UUID，每个用例除原始报告外还产出 `<用例>/manifest.json` 与 `<用例>/trace.json`；批次汇总为 `scores.json`。可用 `node scripts/p17-score-batch.mjs <批次目录>` 重评。批次、租户、主体、Run、Attempt 和流程实例必须相符，不按单号合并；七项全部进入分母，缺件不通过。

- 检索：实际返回的 locator 经 buyer 在本次 RetrievalTrace 下回读，核对原件 SHA 和 Markdown 行号，通过 `anchors.tsv` 对应到业务锚点。正常答案里的引用必须是该次实际召回的 locator。
- 工具：在调用边界记录每次 Attempt，与已落库的幂等键、Worker Trace 和独立只读订单夹具回执核对。超时的两次领取分别记录，而不是根据最终一行 FAILED 审计猜测次数。
- 结果与流程：先读取已提交 Run，再处理完成事件；至少观察 5 秒后查询同实例活动任务与历史，并检查最早人工任务不早于 Run 完成。平台超时/撤权交接记录实际错误码，不生成模型答案或模拟引用。副作用统计只覆盖隔离的订单夹具。

2026-11-10 本机批次 `f6f0d261-59b3-4d1b-be91-1b696c9393cf`：七项均参加，检索、工具、结果与流程各 **7/7**。一项进入 READY_CHECK，四项进入 NEED_RECORDS，两项进入 MANUAL_REVIEW。另将同样进入 NEED_RECORDS 的 EMPTY 与 FOREIGN 互换检索段、审计段、流程段，三次均为 INDETERMINATE；只换嵌套订单回执则工具项 FAIL。

真实方舟入口需在合规公网出口、已配置 `ARK_API_KEY` 和 `WORKFLOW_TEST_ARK_CHAT_MODEL` 的环境**显式运行**：

```powershell
.\mvnw.cmd -pl workflow-boot -am '-Dsurefire.failIfNoSpecifiedTests=false' '-Dworkflow.rag.live-p17=true' '-Dtest=P17JointRunTest#liveRunsUseTheSamePublishedFixture' test
```

该命令先执行生产出站校验，失败时不读取凭据、不发模型请求。当前本机回归不选择此入口；脚本驱动的三项成绩不证明真实模型能自行选对工具，也不代替非超级用户 RLS、真实 MCP 传输或生产验收。
