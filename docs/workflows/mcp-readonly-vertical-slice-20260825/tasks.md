# MCP-1 + MCP-2 任务拆分

> 后续追踪（2026-10-06）：Docker/Testcontainers 通用基础设施门禁已经恢复并通过；T-07 所要求的“确定性 HTTPS MCP Server 容器 + 超时/重启/Flowable 继续运行”专项仍未完成，因此保持未勾选。当前 MCP 状态以 `docs/status.md` 和 `docs/quality/known-issues.md` 为准。

- [x] T-01 新增 Connector、ConnectorVersion、CatalogVersion、ToolSnapshot 和 AgentVersion 绑定迁移，含租户 RLS — AC-03/AC-09
- [x] T-02 建立领域对象、Repository Port、目录审核发布应用服务 — AC-02/AC-03
- [x] T-03 实现 HTTPS Streamable HTTP Endpoint Policy 与 MCP Client Port — AC-01/AC-05
- [x] T-04 实现 initialize、initialized、分页 tools/list 和连接测试 — AC-02
- [x] T-05 实现目录指纹、Schema 校验和 AgentVersion 快照绑定 — AC-03/AC-04
- [x] T-06 实现 McpAgentToolAdapter，接入现有 AgentToolRegistry — AC-05/AC-06/AC-07
- [ ] T-07 增加确定性 MCP Server 容器集成测试和故障测试 — AC-08/AC-09
- [x] T-08 增加最小管理 API 和运行配置说明，不做完整前端 — AC-02/AC-03
- [x] T-09 更新状态、问题记录、学习文档并完成最终评审 — 全部 AC（除 T-07）

说明：本机 Docker 已可用，但 T-07 仍需新增确定性 HTTPS MCP Server 容器并验证超时、重启和 Flowable 继续运行；通用 Testcontainers 基线不能替代该专项验收。
