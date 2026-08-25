# Design: Agent Runtime Hardening

## Selected approach

1. `knowledge_search` 改为显式能力开关：迁移只注册定义，不给租户授权；运行时还要求 Spring 应用配置开启且存在完整检索端口装配。
2. 为 `AgentTool.Request` 增加稳定的 `runId`/逻辑步骤标识输入，由 Runtime 计算幂等键；Attempt、Trace 只作为审计字段，不参与幂等身份。
3. 将执行器进度协议升级为可恢复 Session：每个逻辑步骤产生受限 `CheckpointState`，Worker 在事务中保存；Worker 领取新 Attempt 时读取最后 Checkpoint，并把恢复状态传给执行器。Checkpoint 采用版本化 JSON，保留游标、上下文摘要、工具调用摘要和结果引用。
4. `PlatformAgentExecutor` 的协议修复 Prompt 使用已计算的 `effectiveTools`，不能再使用注册表全集。
5. 发布校验使用一个应用端口查询平台注册定义、租户授权和工具能力，集中返回所有非法工具，而不是运行时过滤。

## Alternatives considered

- 只在数据库中把 `knowledge_search` 授权删除：拒绝，因为新租户触发器或手工授权仍可能误开，缺少应用层能力门禁。
- 使用 Trace ID 作为幂等键：拒绝，因为 Trace 每次 Attempt 都会变化，不能表达业务步骤身份。
- 继续只记录 Step 审计：拒绝，因为审计不能恢复执行上下文。
- 发布时允许未知工具、运行时静默过滤：拒绝，因为配置错误会延迟到生产运行才暴露。

## Affected surfaces

- Code: `agent-engine` 工具注册、Agent Executor、Worker、AgentDefinition 发布服务；`workflow-boot` 知识工具装配。
- API/data: 工具授权迁移、AgentVersion 工具校验、Checkpoint JSON 版本和恢复游标。
- Configuration: `knowledge_search` 默认关闭，真实 Retriever 装配后显式开启。
- Operations/migration/rollback: 已有版本保持只读兼容；新字段使用默认值；关闭能力不删除历史运行记录。

## Decisions

- DEC-01: 幂等键由 `tenant + runId + logicalStepId + toolCode + argumentsHash` 构成，数据库唯一约束继续包含 tenant/tool/key。
- DEC-02: Checkpoint 只保存受限状态，不落 Provider credential 和完整原始响应。
- DEC-03: 发布失败信息按工具代码聚合，便于前端一次修复全部配置问题。
