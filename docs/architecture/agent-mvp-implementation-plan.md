# Agent MVP 实施边界

状态：首个纵向执行闭环已完成；生产级 Agent MVP 尚未完成。

更新时间：2026-11-11

本文只保留 MVP 的边界、依赖和验收，不再复制领域模型、SQL 和历史实现过程。长期原则见[长期设计总览](long-term-design.md)，当前工作顺序见[下一步计划](../status.md)，具体缺口见[待修复问题](../quality/known-issues.md)。

当前已完成项属于原生后端；目标内部运行时改由 [LangGraph](langgraph-runtime-adoption-design.md) 承担。下面的业务验收仍保留，但新图不重复实现 Java 内部游标、Run 心跳和 Recovery。

## 已完成

- AgentDefinition、不可变 AgentVersion、Provider 和凭据加密。
- AgentRun、Attempt、Step、Checkpoint、State History 和 Model Invocation 运行账本。
- `MODEL_ONLY` Provider 执行、输入/输出 Schema 校验、基础结果策略、重试、租约心跳和 Recovery。
- Flowable Agent Service Task、部署期绑定校验、Outbox/Inbox 完成事件和幂等流程恢复。
- 首节点及审批后 Agent 的输入契约生成、前端动态输入和后端命令边界校验。
- Provider 能力契约、脱敏错误摘要、类型化恢复决策、人工重试操作账本及租户运行数量筛选；跨实例硬配额仍见 P1-6，不能以 COUNT 筛选代替。
- 平台 Agent 多步骤 Checkpoint 恢复、活动运行取消、协作式暂停/恢复和显式 BPMN 人工复核分支。
- Runtime 健康摘要、低基数指标、Prometheus 告警、Runbook 和前端状态化运维操作。
- PostgreSQL、Redis、Flowable、Flyway、RLS 和 HTTP 安全的容器化集成测试基线。

## MVP 剩余范围

1. 原生 Heartbeat 已有共享池、活动注册上限和容量指标，旧运行排空前保持保护；新图按 G0–G3 验证 runtime 租约/恢复及平台调用配额。两条路径都需真实强杀、旧结果隔离和跨实例测试，不为新图另建 Java 批量内部续租器。
2. [Checkpoint v5](agent-collaboration-design.md) 已实现有预算的增量笔记、按步回执回读和声明式决策门禁；同租户采购已覆盖工具成功但未写回执、已验证答案但未提交 Run，以及降低摘要预算后的恢复；已提交的工具和候选答案均不依赖模型重现。下一步验证跨进程故障窗口、模型与 Token 预算差异，将 RAG Evidence/超大对象接入独立存储并扩展多领域等价评测。
3. 完整 Guardrail、证据约束、高风险写工具审批和 UNKNOWN_OUTCOME 人工核验。
4. Provider 出站安全、租户/Provider 跨实例公平配额、背压和降级。
5. 版本化业务结果策略、成本预算和告警到处置的生产运营闭环。
6. Flowable 深层 RLS、真实 Provider/MCP Server 兼容矩阵和端到端故障注入。

## 不能突破的实现边界

- 手动测试和流程触发必须复用同一套 AgentRun 执行链路。
- Flowable 只保存流程状态和业务变量；模型调用不得在 Flowable 事务内同步执行。
- Agent 运行状态只能由 Agent 状态机推进，迟到 Attempt 不得覆盖当前 Attempt。
- 完成事件必须经过版本化 Inbox、节点激活校验和显式输出映射后才能恢复流程。
- 新能力必须扩展现有运行账本和应用端口，不得另建旁路执行记录。

## MVP 完成判定

只有执行、恢复、幂等、权限、审计、取消、人工交互和 Flowable 集成全部通过容器化集成测试，才可将 Agent MVP 标记为完成。
