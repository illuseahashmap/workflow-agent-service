# Agent Runtime Hardening

## Goal

修复 `knowledge_search` 和 Agent 工具运行链路中的安全、幂等、恢复与发布校验问题，使未完成的知识检索能力默认不可用，并让工具调用可以跨 Attempt 保持稳定语义。

## Scope

- In scope:
  - 未完成真实 Retriever/权限策略前，`knowledge_search` 不自动启用或授权。
  - 工具幂等键与 Run、逻辑步骤、工具和参数绑定，与 Attempt/Worker/Trace 解耦。
  - Checkpoint 保存可恢复执行所需的稳定步骤、游标、上下文和工具结果引用，并在新 Attempt 恢复。
  - 工具协议修复提示只暴露当前有效工具集合。
  - AgentVersion 发布时校验工具已注册、租户已授权、工具 Schema 可用且执行模式兼容。
- Out of scope:
  - 本轮不实现 PostgreSQL/pgvector Retriever、摄取 Worker、MCP 连接器或写工具。
  - 不改变已有 AgentRun 状态机和租户边界。

## Constraints and risks

- 旧 AgentVersion 和旧 BPMN 必须保持可读；迁移必须可回滚或兼容已有数据。
- 工具调用只能继续经过 `AgentToolRegistry`，不能绕过授权、Schema、审计和幂等校验。
- Checkpoint 不保存凭据或无界模型原文；上下文和工具结果必须受大小限制。
- 当前环境可能没有 Maven/Docker，测试证据需明确区分已运行和受阻检查。

## Acceptance criteria

- AC-01: 新迁移后 `knowledge_search` 默认不启用、不自动授权；只有真实 Retriever、授权策略和应用服务装配完成后才可显式启用。
- AC-02: 同一 AgentRun 的同一逻辑工具步骤在不同 Attempt/Trace 下生成相同幂等键；参数变化必须被拒绝，不得重复执行已成功的只读/未来写工具。
- AC-03: 每个可恢复步骤持久化稳定逻辑步骤 ID、下一步游标、受限上下文和工具结果引用；新 Attempt 能读取最后完整 Checkpoint 继续执行，而不是从规划阶段无条件重跑。
- AC-04: 工具调用格式修复提示只包含本次有效工具集合，不泄露平台或租户未授权工具名称。
- AC-05: 发布包含未知、未授权、Schema 缺失或执行模式不兼容工具时失败，并返回明确配置错误。
- AC-06: 单元测试覆盖上述行为；OpenAPI/静态检查通过；文档明确剩余的真实 RAG/MCP 工作。

## Open decisions

- DEC-01: Checkpoint 上下文只保存受限运行状态和工具结果摘要/引用，不保存完整 Provider 原始响应。
- DEC-02: 工具可用开关采用平台配置默认关闭 + 数据库授权默认关闭双重门禁。
