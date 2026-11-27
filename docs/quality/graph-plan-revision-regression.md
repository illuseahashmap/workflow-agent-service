# 计划修订与共享纠偏验收（V76）

日期：2026-11-20。在 V74 初始规划和 V75 物理调用事实基础上，为显式启用的规划策略 v2 增加一次 revision 1 → 2 修订。未修改业务库、发布 artifact 或切换业务版本。

## 启用和职责

新发布版本在既有 x-graph-checklist 之外声明 `"x-graph-planning":{"version":2,"mode":"PLANNED"}`，且清单 allow_correction=true。策略随 Run 冻结。v1 binding、无规划 v2、规划策略 version=1 保持原行为：不自动升级历史 Run，不增加重规划调用。旧 revision 1 的七字段序列化保持原样；严格解码仅为旧计划补入内部空修订元数据，不放宽其他字段或新 revision 的校验。

LangGraph 持有接受回执、plan_revision 和当前待执行请求；Java 保存不可变计划裁决、工具意图与计划的关联、权限/证据和预算，不维护 nextStep。V76 将裁决从每 Run 一条扩展为按稳定 validation operation 追加，保留旧外键、RLS 和不可变触发器；接受的 revision 在 Run 内唯一。

## 一次机会与安全切换

循环检测触发时沿用 agent_graph_correction 的每 Run 唯一许可。策略 v1 仍选择普通纠偏；策略 v2 使用稳定 `plan:correction:` operation，并返回 REPLAN_REQUIRED。它与普通纠偏互斥，不额外领取第二次机会。未来格式修复必须复用此许可；本轮尚未实现格式修复节点或其他自动重规划触发器。

图丢重定向、规划模型回执或接受回执时，都恢复同一个 operation/candidate/validation。重规划模型返回只产生候选，不完成纠偏；计划裁决、验收回执及 correction_result 在同一 Run 锁与事务内提交。普通探索在此之前被 CORRECTION_PENDING 阻止。

revision 2 必须满足：

- plan_id、主体与任务契约不变，parent_revision=1，必要事实、允许能力、DAG 和最终规则仍全部校验。
- 提供有界 revision_reason 与非空 evidence_refs；引用只能来自该 Run 原因果输入中已提交、经当前授权检查的工具回执。
- 忽略条目名称、描述及数组顺序后，事实分组/能力/依赖结构必须发生变化；纯改名不接受。这是确定性结构门禁，不宣称模型语义已被证明正确。
- 在 Run 锁下比较当前接受的父版本，检查剩余执行预算；有任何 ACTIVE/QUARANTINED 物理许可时产生 PLAN_SWITCH_PENDING；正式宿主将其转换为稳定 wait_id 的 RETRY 等待，让图持久挂起后恢复同一验证请求。不生成新模型提议，不凭 TTL 释放旧调用。

每次真正发送工具前再次验证当前计划版本，防止预算预留与发送之间发生切换。旧意图不能借更换调用代次再次发送。已成功的旧工具账本仍可经当前权限核验回读，回执仍绑定原始意图；必要事实按冻结规则核验，不直接复制旧计划的步骤完成状态。新计划不会清空循环历史、工具轨迹或预算。

首版只有一次自动纠偏，因此最多接受 revision 2。没有通用任意次数 replanning、并行计划分支或 revision 3。无效、纯改名、证据不合法或预算不可行的修订产生持久拒绝，图按原失败交接路径无模型收尾；未知外呼保持 V75 隔离语义。

## 验收

真实 PostgreSQL 用例覆盖修订事务回滚、换代/丢 ACK 恢复、并发接受去重、旧意图发送屏障与旧证据回读、未知许可阻止切换、纯形式修订拒绝、修订后预算不足直接收尾，以及切换后再次循环不能重新领取纠偏。Python 覆盖触发、提议、接受及切换等待四处冷恢复，并区分 HTTP 重定向与可重试等待。规则测试覆盖证据引用、结构变化、旧计划序列化和既有权限/主体/DAG 规则。

全量 `uv run pytest -q`：**85 passed（470.43 秒）**；内嵌 Java **202 tests，0 failures，0 errors，0 skipped**，含 111 项 PostgreSQL 持久层和 6 项真实 Flowable 测试。Python 与内嵌 Java 数量不相加。日志：`C:/Users/email/AppData/Local/Temp/pytest-of-email/pytest-21/test_java_tool_gateway_postgre0/java-gateway.log`。

最终复核统一了重新规划提示中的 revision 2 要求，并限制初始 revision 1 不携带修订元数据；随后定向执行 **19 项全部通过**（14 项计划规则、5 项修订 PostgreSQL 场景）。测试同时断言重规划提示不混入 revision 1 指令。此处均为重跑，不增加全量测试数量；这两项局部调整后没有重复整个 Flowable 套件。日志：`C:/Users/email/AppData/Local/Temp/graph-revision-final-rww40xii/revision.log`。Ruff、18 个 Python 文件格式检查及 `git diff --check` 通过。

复现使用 agent-runtime-langgraph 下 `uv run pytest -q`，配置 JAVA_HOME、MAVEN_ARGS、WORKFLOW_LANGGRAPH_TEST_PG_BIN 指向隔离测试环境；不能使用业务数据库。环境：Java 25、uv Python 3.12、临时 PostgreSQL 18。迁移在最小基线夹具上执行，不代表完整生产升级演练；未调用真实模型或切换业务版本。
