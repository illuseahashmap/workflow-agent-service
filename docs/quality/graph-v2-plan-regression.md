# PLANNED revision 1 验收

后续 V76 为显式策略 v2 增加一次修订；本文保留 V74 历史验收，最新边界见[修订记录](graph-plan-revision-regression.md)。

日期：2026-11-20。V74 在 V73 的冻结任务契约上增加显式规划、确定性验收与动作绑定。保留 v1 和未启用规划的 v2；未执行业务库迁移、发布新 artifact 或切换业务版本。

## 启用与边界

发布版本 output_schema 同时声明 V73 的 `x-graph-checklist` 和 `"x-graph-planning":{"version":1,"mode":"PLANNED"}`，新 Run 才冻结规划策略。只有 CHECKLIST 或空 schema 的旧入口不额外调用模型；历史 Run 不回填。

首版只接受 revision=1、parent_revision=0。Plan 是声明式建议；Schema 不包含脚本、SQL、任意服务 URL 或模型完成标记等执行字段，未知字段会被拒绝，验收规则不能弱化。goal 仅作描述，不会被解释执行。计划最多 32 步、序列化正文最多 64 KiB，需满足：

- task_contract_ref 与冻结清单摘要一致，subject_refs 与清单主体完全一致。
- 每项必要事实恰好被一个条目覆盖；step_id 唯一，依赖存在且无环。
- capability_choices 为该条目事实对应的工具，且当前仍获授权；不支持任意辅助工具或增加业务主体。
- success_rule_refs 必须等于条目的 fact_ids，missing_evidence_action 固定 REJECT。
- 有足够的逻辑调用、物理调用、模型、工具及最终校验预算。此处按每种能力至少一次调用估算，实际调用仍逐次扣账，不能保证业务工具一定产生足够事实。

## 执行与恢复

图先提交普通动作选择意图；若冻结策略要求规划，Java 在任何模型外呼前返回 PLAN_REQUIRED。图显式经过 prepare_plan → MODEL → prepare_plan_validation → VALIDATE，再进入动作选择。未通过计划验收不能调用业务工具。

规划 MODEL 的 ID 为 `plan:` + binding digest，验收 ID 为 `plan-validate:` + binding/candidate 摘要。原模型响应先持久化，验收回执和计划裁决同事务提交；丢 ACK、事务回滚或换执行代次都复用同一原响应/回执。图持有 plan_receipt_ref，平台保留不可变计划和工具意图的条目绑定，不维护第二套 nextStep。

PLANNED 首版每个模型决策只允许一个 TOOL_CALL；必须携带 plan_id、整数 plan_revision=1、字符串 plan_step_id。参数的主体字段须是与冻结主体相同的字符串，不接受数字转文本或整数溢出。发布工具意图时原子绑定已接受计划与条目；执行前再次验证全部前置依赖的事实、主体、当前工具权限，并重新检查依赖工具证据的读取权限。模型不能标记条目完成；前置条件只能由已提交的可信回执支持。

计划接受不等于业务成功：ACK 拒绝把计划接受回执当作最终结果，最终验收仍要求所有必要事实。无效/不可行计划有持久拒绝回执，可直接无模型收尾；不自动追加格式修复。非法动作提议不能发布工具意图，重放复用原响应；若图无法推进，既有 Run deadline 与停止收尾仍兜底。

本版没有动态 revision 切换、重新规划、并行分支或通用语义评审。循环纠偏继续使用 V73 的同一 Run 机会，在已接受计划内调整动作，不能借此换计划或清空历史。未来重规划/格式修复必须共享该额度；完整 P-17 金标、严格 Token/费用预算与容量/网络分区门禁仍待完成。

## 验证

规则测试覆盖覆盖率、主体/契约绑定、能力授权、依赖/DAG、重复 ID、失败策略弱化、伪造完成及数值类型保护。Python 覆盖正常规划与规划响应/接受回执丢 ACK 后冷恢复。真实 PostgreSQL 覆盖计划裁决事务回滚、换代重放、意图绑定、无效条目无外呼、拒绝计划收尾、预算不足、两步事实依赖、不可变裁决与租户隔离。保留既有预算、一次纠偏、停止、RLS 和真实 Flowable 回归。

全量回归：`uv run pytest -q` **80 passed（383.66 秒）**；内嵌 Java **182 tests，0 failures，0 errors，0 skipped**，包含 95 项 PostgreSQL 持久层及 6 项真实 Flowable 测试。Python 与嵌套 Java 数字不相加。

随后复核补强了传递依赖：即使中间条目的事实提前出现，也不能跳过其尚未满足的祖先依赖。最终代码定向回归 **18 项通过**（12 项计划规则、6 项计划 PostgreSQL 链路），其中 17 项是重跑，1 项为新增传递依赖测试；不把重跑计为新增成绩。没有在这次纯规则修正后重复执行整个 Flowable 测试集。

Ruff 检查、格式检查（18 个 Python 文件）和 `git diff --check` 通过。环境：Java 25、uv Python 3.12、独立临时 PostgreSQL 18；未调用真实模型。全量日志：`C:/Users/email/AppData/Local/Temp/pytest-of-email/pytest-16/test_java_tool_gateway_postgre0/java-gateway.log`；定向日志：`C:/Users/email/AppData/Local/Temp/graph-plan-final-vyn06nu7/java-plan-final.log`。日志为临时文件。

全量复现入口仍为 agent-runtime-langgraph 下 `uv run pytest -q`，配置 JAVA_HOME、MAVEN_ARGS、WORKFLOW_LANGGRAPH_TEST_PG_BIN，禁止指向业务库。定向验收在另一独立临时集群运行 GraphPlanTest 与六项计划持久层用例，完成后集群已停止。
