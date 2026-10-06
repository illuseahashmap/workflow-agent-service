# Agent Runtime 可运营与可靠性加固

## Goal

在不引入 MCP、RAG 专项逻辑的前提下，把 Agent Runtime 从“可查询”提升为“可告警、可定位、可处置、可恢复”的平台底座。

## Scope

- 低基数运行指标、积压与死信指标、Prometheus 告警和处置手册。
- 租户范围运行健康摘要，以及平台管理员可执行的失败处置入口。
- AgentRun 协作式暂停、恢复、取消和人工重试操作账本。
- 暂停请求只在持久化 Checkpoint 或外部调用前后的安全边界生效。
- 前端运行记录页展示健康摘要，并在详情中提供与状态匹配的操作。
- 单元测试、契约测试、前端 E2E 和浏览器真实验证。

## Out of scope

- MCP 工具协议、写工具确认和 MCP 出站安全专项。
- RAG 向量检索、Grounding、Citation 和评测专项。
- 在没有容器运行时伪造 PostgreSQL、Redis 或多实例故障注入结果。

## Invariants

1. 暂停不能丢失已完成步骤；恢复只能从最后完整 Checkpoint 创建新 Attempt。
2. 运行中的暂停是协作式请求，不能通过杀线程破坏 Provider/MCP 客户端状态。
3. 旧 Worker 在暂停、取消或接管后不能写入终态或覆盖新 Attempt。
4. Prometheus 指标不得使用 tenantCode、runId、traceId 等高基数标签。
5. 运维命令必须租户隔离、权限校验、幂等并写入操作账本。
6. Flowable 在暂停期间继续停留于原 Agent 节点。

## Acceptance criteria

- AC-01：运行健康摘要返回各状态数量、最老排队时间、过期租约和需人工处理数量。
- AC-02：Prometheus 暴露运行结果、耗时、积压、暂停、恢复、死信与租约指标，告警均有 Runbook。
- AC-03：QUEUED Run 可立即暂停；RUNNING Run 在安全边界转为 PAUSED；重复暂停幂等。
- AC-04：PAUSED Run 可恢复为 QUEUED，保留 Checkpoint 并创建新 Attempt；重复恢复不会重复执行命令。
- AC-05：取消、暂停、恢复、重试均写入状态历史和操作账本；无权限或跨租户操作失败。
- AC-06：前端可查看健康摘要，并只对合法状态展示暂停、恢复、取消和重试操作。
- AC-07：后端单元/静态检查、前端单元/E2E/构建通过；浏览器真实验证主路径。
- AC-08：Docker 不可用时明确列出未执行的容器故障测试，不得标记生产验收完成。
