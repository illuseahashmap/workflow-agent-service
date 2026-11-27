# 双采购、连续压缩与换代恢复组合验收

日期：2026-11-27。基线为已推送的 `c81c528`（`codex/v79-route-p17`）。本轮仅新增测试与证据，不更改生产协议、数据库迁移或旧版行为，不包含 V80。

## 场景与结果

同租户的两笔采购使用同一版本和 artifact、不同业务输入与 Run。以单次调用切片交错推进，每次重新打开官方 PostgreSQL saver 和图实例。每笔采购执行两次工具查询、两次增量压缩和两次原文回读，并在首次压缩后、第二次压缩已落库但响应丢失后分别换代。

| 检查 | 结果 |
| --- | --- |
| 连续压缩与换代 | 每笔 2 次压缩、2 次换代，最终 generation=3；第二摘要链接本 Run 的第一摘要，并保留两份来源 |
| 增量输入 | 第二摘要包含上一摘要和新工具结果，不重新注入第一份原文或截断尾部 |
| 响应丢失恢复 | 共 14 次模型调用、4 次工具调用；每笔物理预算账本 9 次，无重复执行 |
| 同租户串单 | 交换两笔的摘要引用和工具请求，4 次全部拒绝；模型输入不包含另一笔标识 |
| 旧代隔离 | 旧 generation 推进被拒且 checkpoint 不变；终止旧 PostgreSQL 会话、后继获取锁后，4 次旧连接迟到写均失败且无残留 |
| 原文精度 | A=`20000.000000000001`，B=`19999.999999999999`；READ_RESULT 保留原始精度 |
| 事实优先 | B 的摘要与最终候选故意声称 approved=true，原始工具事实为 false，最终仍以 GRAPH_FACT_REJECTED 拒绝 |
| 业务提交 | A SUCCEEDED，B FAILED 且无最终输出；每笔只有 1 次业务 Attempt，总计 2 条完成 Outbox；第三次完成扫描无工作 |

机器结果见 [JSON 证据](evidence/graph-dual-purchase-20261127.json)。测试入口为 `GraphPythonRecoveryIntegrationTest.dualPurchasesRemainIsolatedAcrossTwoSummariesAndGenerationRecovery`，Python 探针为 `agent-runtime-langgraph/tests/dual_purchase_probe.py`。

## 验证方式与边界

使用 Java Gateway/JDBC、真实 PostgreSQL、Python GovernedAgentGraph 和官方 PostgresSaver。模型与只读工具为确定性适配器；业务表为最小测试夹具，完成服务使用测试 Outbox。换代入口仅属于测试服务器，不是生产 API。此组合不经过生产容量网关，不代表两笔真实 Flowable 流程、外部 MCP/Provider 或并发负载验收。

两笔运行采用交错切片，非并行压力测试。旧连接终止用于验证会话失效后的迟到写隔离，不替代完整网络分区测试。先前真实 Flowable 的图端/原生 P-17 七场景各 7/7，记录在 [持久收尾回归](graph-tool-stop-regression.md)，不是本轮重新执行的七场景结果。

本轮完整回归：103 项 Python、252 项 Java，零失败、零跳过；其中跨语言恢复 6 项、真实 Flowable 完成回归 6 项。Checkstyle 与四个关键治理类的 JaCoCo 门禁通过；新增 Python 探针的 Ruff 检查和格式检查通过。复现使用独立空 PostgreSQL 数据库，设置 `WORKFLOW_LANGGRAPH_TEST_DATABASE_URL` 后在 `agent-runtime-langgraph` 执行 `uv run --offline pytest -q`；该入口包含 Java 联合回归、Checkstyle 与治理覆盖率门禁。定向 Java 用例需设置 JDBC 与 checkpoint DSN，并初始化官方 checkpoint 表。

后续已将这组组合接入真实 Flowable 提交、认证派发和生产 Outbox/Inbox，见[后续记录](graph-dual-purchase-flowable-regression.md)。本文保留当时的夹具证据；后续仍需补真实 Provider/MCP、未知调用对账和多实例故障/负载证据。完整 G1/G2、业务版本切换与原生退役仍未完成。
