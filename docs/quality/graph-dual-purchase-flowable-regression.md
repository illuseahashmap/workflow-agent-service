# 双采购压缩恢复：真实 Flowable 派发与完成验收

日期：2026-11-27。前轮验收已推送为 `4955126`，分支 `codex/v79-route-p17`；本轮延续该独立分支，不包含 V80。

## 链路与结果

测试发布真实 AgentVersion 和工具快照，再部署采购 BPMN。两笔同租户采购分别由 Flowable 的 `AgentTaskExecutionListener` 调用正式提交服务创建 Run，输入、节点绑定、激活标识和失败策略均来自流程。测试为选定 Run 预约图运行，Python 通过正式 `HttpDispatchClient` 和 `GraphHostController` 完成认证领取、批量续租、换代后的重新领取及 ACK。调用经过 `GraphCapacityGateway`、实际 JDBC 回执与预算存储，使用官方 PostgreSQL saver。

每笔采购执行两次查询和两次增量压缩：首次压缩后换代一次，第二次摘要已落库但 HTTP 响应丢失后再换代一次。沿用前轮串单、旧代推进、旧连接迟到写和原文回读检查。最后通过正式 `GraphCompletionService`、Run/Attempt、生产 Outbox/Inbox 和 `AgentCompletionRecoveryService` 回到各自 Flowable 流程。

| 检查 | 本轮结果 |
| --- | --- |
| 压缩与换代 | 每笔 2 次压缩、2 次换代，最终 generation=3，共 28 次冷开图/saver |
| 幂等调用与预算 | 总计 14 次模型、4 次工具调用；每笔物理预算账本 9 条，摘要响应丢失没有重复调用 |
| 隔离 | 4 次交换引用/工具请求拒绝，4 次旧连接迟到写拒绝；旧 generation 无法续租或推进 |
| 业务 Attempt | 每笔只有 1 次，换代不创建新的业务重试 |
| 成功采购 A | Run SUCCEEDED，唯一人工任务 READY_CHECK；映射到流程变量的金额仍是 `20000.000000000001` |
| 否决采购 B | 原始金额 `19999.999999999999`、原始 approved=false；尽管摘要/候选故意声称 true，Run 仍以 GRAPH_FACT_REJECTED 失败并进入唯一 MANUAL_REVIEW，无候选输出和金额变量泄漏 |
| 完成消息 | 两条生产完成 Outbox 均 DELIVERED，两条 Inbox 完成；将已送达消息重新置为 PROCESSING 后再次消费，Inbox 重放分支不再次推进流程 |
| 流程隔离 | 各流程保留自己的输入标识，当前任务和历史人工任务均恰好一条；两笔 workflow_resumed_at 均已写入 |

机器结果见 [JSON 证据](evidence/graph-dual-purchase-flowable-20261127.json)。入口为 `GraphDualPurchaseFlowableIntegrationTest.flowableDualPurchaseCompressionAndGenerationRecovery`，复用 `tests/dual_purchase_probe.py` 的派发模式；原先直接 Gateway 模式仍保留并纳入回归。

## 验收发现并修复的根因

新增流程变量断言复现了 `20000.000000000001 → 20000.0`：`AgentOutputMappingResolver` 用默认 Jackson 浮点解析读取快照/字符串内容，数值在输出映射边界丢失精度，图端原文与 READ_RESULT 本身没有丢失。

现使用局部精确小数 reader 读取快照和嵌套字符串内容，不改全局 ObjectMapper。映射对象、数组和投影递归保留数值：普通小数能以 Double 往返保持十进制值时维持旧类型；若会损失数值、溢出或下溢则使用 BigDecimal。显式配置 BigDecimal 的调用方继续保持该策略；整数、布尔、字符串、空值、既有映射路径和内部变量保护均保留。已有持久化的流程变量不会被改写。

新增单元测试覆盖对象/字符串两类快照、嵌套数组/投影、精确金额、超大/超小值、普通 Double 和共享 mapper 配置不被修改。真实 Flowable 组合同时验证该金额写入后可无损读回。

## 测试与边界

- 完整跨语言联合回归：**103 Python、257 Java，零失败、零跳过**；包含原有 6 项跨语言恢复、6 项真实 Flowable 完成测试和本轮组合测试。
- Checkstyle、关键治理类 JaCoCo 门禁、新增/修改 Python 探针的 Ruff 与格式检查通过。
- 公共输出映射影响范围追加旧版绑定、输入映射、恢复、变量保护、流程运行和 XML 解析回归：**31 项通过，零跳过**。其中 10 项与联合回归重叠，不将两组简单相加。
- 复现：为 `WORKFLOW_LANGGRAPH_TEST_DATABASE_URL` 指定独立空 PostgreSQL，在 `agent-runtime-langgraph` 执行 `uv run --offline pytest -q`。联合入口强制检查本轮 Flowable 测试报告非零且不跳过。定向 Java 测试使用 `WORKFLOW_LANGGRAPH_JDBC_URL/USER/PASSWORD`，自动创建并清理自己的数据库。

模型和只读采购工具仍为确定性适配器，非真实 Provider/MCP；两笔采用交错切片，非并发压力测试。HTTP 测试桥使用真实控制器/认证协议，但不是完整 Spring Web 安全过滤器部署。测试仅显式预约选定 Run，生产自动后端选择、宿主默认关闭状态和业务版本发布策略未改变；测试专用的租约过期端点不属于生产 API。旧会话强制终止验证迟到写隔离，不替代完整网络分区报告。

本轮关闭双采购/连续压缩/换代恢复的确定性模型真实 Flowable 组合证据缺口；完整 G1/G2、生产容量和业务版本切换仍未完成。后续继续未知调用对账与多实例故障/容量验收；真实 Provider/MCP 按既定安排另行验证。
