# Agent MVP 实施边界

状态：首个纵向执行闭环已完成；生产级 Agent MVP 尚未完成。

更新时间：2026-10-06

本文只保留 MVP 的边界、依赖和验收，不再复制领域模型、SQL 和历史实现过程。长期原则见[长期设计总览](long-term-design.md)，当前工作顺序见[下一步计划](../status.md)，具体缺口见[待修复问题](../quality/known-issues.md)。

## 已完成

- AgentDefinition、不可变 AgentVersion、Provider 和凭据加密。
- AgentRun、Attempt、Step、Checkpoint、State History 和 Model Invocation 运行账本。
- `MODEL_ONLY` Provider 执行、输入/输出 Schema 校验、基础结果策略、重试、租约心跳和 Recovery。
- Flowable Agent Service Task、部署期绑定校验、Outbox/Inbox 完成事件和幂等流程恢复。
- 首节点及审批后 Agent 的输入契约生成、前端动态输入和后端命令边界校验。
- Provider 能力契约、脱敏错误摘要、类型化恢复决策、人工重试操作账本和租户级并发上限。
- 平台 Agent 多步骤 Checkpoint 恢复、活动运行取消、协作式暂停/恢复和显式 BPMN 人工复核分支。
- Runtime 健康摘要、低基数指标、Prometheus 告警、Runbook 和前端状态化运维操作。
- PostgreSQL、Redis、Flowable、Flyway、RLS 和 HTTP 安全的容器化集成测试基线。

## MVP 剩余范围

1. Worker 强杀、租约过期、运行中外部调用边界暂停和跨实例恢复压力验证。
2. 完整 Guardrail、证据约束、高风险写工具审批和 UNKNOWN_OUTCOME 人工核验。
3. Provider 出站安全、租户/Provider 跨实例公平配额、背压和降级。
4. 版本化业务结果策略、成本预算和告警到处置的生产运营闭环。
5. Flowable 深层 RLS、真实 Provider/MCP Server 兼容矩阵和端到端故障注入。

## 不能突破的实现边界

- 手动测试和流程触发必须复用同一套 AgentRun 执行链路。
- Flowable 只保存流程状态和业务变量；模型调用不得在 Flowable 事务内同步执行。
- Agent 运行状态只能由 Agent 状态机推进，迟到 Attempt 不得覆盖当前 Attempt。
- 完成事件必须经过版本化 Inbox、节点激活校验和显式输出映射后才能恢复流程。
- 新能力必须扩展现有运行账本和应用端口，不得另建旁路执行记录。

## MVP 完成判定

只有执行、恢复、幂等、权限、审计、取消、人工交互和 Flowable 集成全部通过容器化集成测试，才可将 Agent MVP 标记为完成。
