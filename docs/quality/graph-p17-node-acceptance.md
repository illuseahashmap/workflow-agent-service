# P-17 图场景与节点组合验收

> 后续：超时和撤权收尾已修复，最新图端与原生均 7/7，见[持久收尾记录](graph-tool-stop-regression.md)。下文保留初轮 5/7 的历史现场。

日期：2026-11-27。路由提交 `7064e9f` 已推送至 `codex/v79-route-p17`，分支以 `1e9176a` 为基线，不含另一项工作的 V80。本文记录其后的验收增量，不代表已推送或业务版本切换。

## 结论

原生 P-17 **7/7**；图端 **5/7**，完整七项均进入分母，验收脚本以失败退出。不能宣布 P-17、阶段二或 G1/G2 通过。完整评分和失败现场见[机器证据](evidence/p17-graph-20261127.json)。

| 场景 | 图端结果 |
| --- | --- |
| B08、P17-OK、P17-BUDGET、P17-EMPTY、P17-FOREIGN | PASS，使用真实已发布绑定、检索、工具审计、Run 和 Flowable 活动任务评分 |
| P17-TIMEOUT | 未通过：两次真实订单调用均 TIMEOUT，RPC 返回 GATEWAY_INTERNAL_FAILURE；观察结束时 Run 仍 RUNNING，持久停止数 0 |
| P17-REVOKED | 未通过：真实删除授权后返回 GATEWAY_TOOL_REJECTED，无后续订单调用；观察结束时 Run 仍 RUNNING，持久停止数 0，未交接人工任务 |

图批次：`876c0bc0-0194-4eea-9127-53abf4766ebc`。原生批次：`8c8b1032-dc99-4b0f-b0f3-72c77fe9e327`。失败场景无终态轨迹，评分为 INDETERMINATE，不以安全拒绝代替业务收尾，不补造通过报告。

## 验收范围和兼容性

新增显式入口 `scripts/test-p17-graph.ps1`，复用原生七场景夹具与评分。模型为确定性脚本，订单 MCP 是进程内只读夹具；发布、授权删除、知识读取、Java Gateway、真实 Python LangGraph、PostgreSQL checkpoint、最终接纳及 Flowable 是实际代码。每个切片关闭并重新打开 checkpoint 连接和图，验证冷恢复。

入口直接组合 Cognition/Tool Gateway，不经生产派发宿主或容量封装，不能据此宣称容量、生产调度、真实 Provider 或远程 MCP 传输验收完成。测试结束对未完成预约执行清理撤销，此清理不算业务终态。超时探针只观察两次可重试错误，不能称为生产重试策略已耗尽，亦不排除全局截止时间随后收尾。

trace v1 只增加可选 `executionBackend`，旧原生报告保持有效；图端校验稳定 intent 幂等键和本次 sourceTrial，不允许串用原生或别的 Run 证据。未修改金标和七场景要求，正常图 resume 不增加业务 Attempt。评分器的原生超时两次 Attempt 基线仍保留；后续图端超时通过必须补齐物理重试身份及证据适配，不能靠增加业务 Attempt 迎合评分。

节点组合测试叠加路由、CHECKLIST、PLANNED、增量摘要、READ_RESULT 和最终格式修复；模拟候选及校验回复丢失后冷恢复，断言模型 7 次、校验 4 次、业务工具仅 1 次、摘要 1 次、格式纠偏 1 次，以及原始金额 `20000.000000000001` 精度。该组合覆盖不等于所有节点排列或双采购/两次压缩验收完成。

本轮修复有效 MOCK Provider 的空 URL 在配置指纹中触发 List.of 空值异常的问题；非空配置序列化保持不变。新增持久化后重建 Gateway 的重放测试，确认不重复调用模型。

## 下一步根因修复

1. 在工具边界统一 MCP 临时故障的类型化映射。当前 McpClientException 逃过 GraphToolGateway 的 AgentExecutionException 分支，落成通用内部错误。为稳定工具 intent 定义冻结、持久化的物理重试上限，与 Run 总预算共同约束，并补重复投递/宕机恢复证据。
2. 将确定不可继续的授权撤销、工具重试耗尽接入持久停止及无模型收尾，原子完成 Run/Attempt/Outbox，由既有 Flowable 失败策略转人工。覆盖旧 generation、迟到回执及重复收尾，保留旧版契约。
3. 重新跑本入口至七项都有同 Run 的真实终态证据，再继续同租户双采购、两次压缩与恢复、完整迁移退出门禁。此记录不关闭 D-1/P1-3/P1-6。

## 复现

使用 Java 25、Node、uv 及 Docker，或提供有权创建临时库的隔离 PostgreSQL。不得指向业务库。先 `npm ci`，然后从仓库根目录运行：

```powershell
.\scripts\test-p17-local.ps1
.\scripts\test-p17-graph.ps1
```

两种后端分别写入 `workflow-boot/target/p17-runs/<batch>` 与 `workflow-boot/target/p17-graph-runs/<batch>`。每次生成新批次，缺证据不通过；图端脚本即使测试失败仍评分全部七项。组合及旧版联合回归在 `agent-runtime-langgraph` 执行 `uv run pytest -q`，需要 `WORKFLOW_LANGGRAPH_TEST_DATABASE_URL` 指向隔离库。质量验证最终结果见本文下方。

## 质量验证

- `mvnw -pl agent-engine -am clean verify`：通过，包含 Checkstyle、SpotBugs 与模块覆盖门禁。
- 评分及 RAG 契约检查：24 项通过；新增 Python 探针 Ruff 检查通过。
- 原生 P-17：7/7；图 P-17：5/7，脚本失败退出符合未完成验收状态。
- 联合回归执行中发现并修正测试大括号/行长规范；重复测试必须使用新隔离数据库，旧 checkpoint 会污染固定 Run ID 的进程崩溃用例。不得将污染库的结果作为有效验收。
- 全新隔离库联合回归：**103 项 Python、245 项 Java，零失败、零跳过**；包含 5 项跨语言冷恢复（含新增组合）、真实 Flowable 交接和四类关键治理覆盖门禁。
- 全仓测试编译及 Checkstyle 通过。普通 agent-engine verify 的 352 项中有 5 项环境条件跳过；这 5 项已在上述联合回归实际执行并全部通过。
