# 图端路由投影与原生语义等价

2026-11-26，V79。业务版本未切换，原生执行器保留。

## 冻结配置与兼容

新 v2 Run 可在已发布输出 Schema 中显式配置：

```json
{"type":"object","x-graph-routing":{"version":1,"allow_correction":true}}
```

`version` 与 `allow_correction` 均须显式声明；false 表示路由错误直接拒绝。reserve 将策略冻结到不可变表。v1、未声明策略的 v2、既有 Run 和既有检查点沿原路径运行，不回填、不转换。新增的图状态字段是可选字段，成功 wire 回执结构不变；部署须让新策略匹配包含路由节点的新 artifact。

## 原生语义与显式节点

沿用原生 AgentRouteDecision v1 的 camelCase 提议契约和能力匹配规则，能力来自已发布工具元数据快照，不按工具名称猜测。

| 模式 | 工具选择约束 |
| --- | --- |
| DIRECT | 可选 DIRECT 工具，不能包含 RAG/MCP |
| RAG | 至少一个 RAG，不能包含 MCP |
| MCP | 至少一个 MCP，不能包含 RAG |
| RAG_MCP | 同时包含 RAG 和 MCP |
| ABSTAIN | 不选择工具；持久拒绝并无模型收尾 |

有效能力范围仍为平台注册/当前租户授权、发布版本、节点子集及可选 CHECKLIST 的交集。Java 验证路由提议，接受后所有模型工具目录及实际 TOOL 准入进一步限制到 requestedTools；计划也不能选择路由集合之外的能力。恢复复用持久路由，不重新决策。缓存回读与最终业务接纳均重新验证所选工具的当前权限。

`ROUTE_REQUIRED`（409）将图转入 `prepare_route`；稳定 MODEL ID 为 `route:` + bindingDigest。`prepare_route_validation` 使用 `route-validate:` + fingerprint([bindingDigest,candidateRef])，沿用 VALIDATE。路由验收回执独立保存到 route_receipt_ref，不混入业务证据因果列表；路由接受后才进入普通模型或 PLANNED 节点。ACK 和最终接纳都拒绝把路由接受当作业务成功。

`ROUTE_DENIED`（403）拒绝伪造路由身份、未选择工具及非最终候选替换。模型提议未选中的工具时，不发布工具意图：保存为最终拒绝候选，走本地 VALIDATE 和无模型收尾，不等待 deadline 反复恢复。单工具与批量工具均覆盖。ABSTAIN 的图审计原因是 AGENT_ROUTE_ABSTAIN，与原生的 AGENT_ROUTE_NOT_EXECUTABLE 都产生拒绝/失败结果；不宣称两个后端所有错误码逐字相同。

## 共享纠偏、预算和恢复

路由格式/模式/可用能力契约错误可按冻结配置领取唯一 Run 纠偏许可。拒绝回执、路由裁决与 ROUTE_INVALID 许可同事务提交。提交后 `ROUTE_REPAIR_REQUIRED`（409）进入显式 `prepare_route_repair`，MODEL ID 为 `route:repair:` + fingerprint([bindingDigest,validationRequestDigest])。原无效提议从不可变模型记录查取，图端不能替换。

路由修复、循环纠偏/计划修订、最终格式修复共享 `agent_graph_correction` 的每 Run 主键。路由修复消耗后，最终格式修复不能再申请。模型、逻辑、物理及本地校验预算不重置；剩余额度不足、allow_correction=false、业务拒绝信号及 ABSTAIN 不授予修复。修复仍失败则持久拒绝并结束。

V79 将共享许可的父外键由 CHECKLIST 调整为 v2 Run 预算账本：纯路由可独立工作。新增数据库触发器仍要求旧类型许可具有原 CHECKLIST，新 ROUTE_INVALID 必须具有冻结的路由纠偏授权；源模型外键、唯一许可和不可变约束保留。新策略/裁决表强制 RLS，裁决关联验证回执，一个 Run 最多一条接受路由。

原始响应已保存后的重放不重发模型，原请求摘要/代次和完整因果引用继续受既有检查。路由和修复各为单次受治理 MODEL；使用图端既有文本 JSON 协议与 Java 严格解析，不启用适配器内部 json_schema→json_object 隐式重发。原生适配器的结构化输出降级保持原行为；若将来给图增加 Provider 降级，须作为显式、计入物理预算的操作另行验收。本轮等价范围是路由模式、工具授权收紧、拒绝结果及恢复语义，不是 Provider 请求字节或全部错误码等价。

## 验收与剩余范围

数据库专项覆盖五种模式、能力快照、回执重放、原子许可回滚、换代恢复、一次修复、预算和显式关闭、拒绝无模型收尾、路由先于规划、与最终格式修复共享许可、未选工具拒绝、缓存与业务接纳撤权。非超级用户实测新表跨租户不可见，并验证本租户可读。

Python 专项覆盖提议、许可、修复、接受裁决、最终答案等提交后失去响应的冷恢复窗口，以及两种 HTTP 控制流错误不作为网络重试。解析专项覆盖 JSON null、多段 JSON、畸形 JSON 和不支持策略版本；原生解析器只新增 null 提议的稳定拒绝，保留其他旧行为。

本轮只使用确定性模型和隔离数据库；完整 P-17 七场景、双采购连续压缩/恢复、真实 Provider/MCP、容量及生产发布仍需独立验收。下一步优先补图端 P-17 和节点组合证据，不扩展第二套 Java 编排。

默认后端 `mvn verify` 也执行图持久化测试：显式提供 `WORKFLOW_LANGGRAPH_JDBC_URL` 时使用隔离测试库，否则由 Testcontainers 创建 PostgreSQL 17 容器，测试结束后销毁。Docker 不可用时失败而非静默跳过；必需集成测试报告门禁新增 GraphPersistenceIntegrationTest。Python/JDBC 冷恢复用例独立为 GraphPythonRecoveryIntegrationTest 套件（旧 v1/v2 两例，加路由正常/修复两例），仅在联合验收提供检查点 DSN 时运行；联合验收对该套件另行断言零失败、零跳过，不要求纯后端任务安装 Python/uv。保留 JDBC 已有参数，确保每例独立 schema。JaCoCo 原有门槛不降低、不排除路由类，验收不借用前一次累积覆盖率。

2026-11-26 验收完成（基线 `1e9176a` 加本轮 V79 改动）：

- 全模块 `mvn clean verify` 通过；测试套件拆分后，对 `agent-engine` 及依赖重新执行 `clean verify` 通过。Checkstyle、SpotBugs、JaCoCo 门禁通过。
- `node scripts/check-integration-test-results.mjs`：7 组必需基础设施套件均真实执行、零跳过；图持久化套件 145 项通过。环境专用套件保留显式开关，不把跳过当成联合验收通过。
- `uv run pytest -q`：103 项 Python 测试通过，其中联合任务执行 243 项 Java 测试，零失败、零跳过；包含独立的四项 Python/HTTP/JDBC 冷恢复测试。测试夹具使用独立 Run ID，匹配检查点以租户和 Run 隔离的契约。
- 后端完整测试范围下，Agent 行/分支覆盖率为 73.24% / 58.18%；仅图联合测试范围下为 49.17% / 42.40%。两者分别从独立覆盖率数据验证，不混用累计结果。
- Ruff 检查、格式检查与 `git diff --check` 通过。Docker Engine 29.9.3；默认数据库夹具为 PostgreSQL 17，联合验收使用隔离的 PostgreSQL 18 容器。临时容器在验收后关闭。

验收使用独立 worktree，排除了另一项工作同时进行的 V80 容量配置改动；主工作区仅合并本轮变更，保留其容量代码、测试和文档。该结果不替代 V80 的独立验收，也不表示已切换业务执行版本。
