# MCP-1 + MCP-2 技术设计

## 领域与模块边界

MCP 继续位于 `agent-engine` 的工具基础设施边界，不新增 Maven 模块。Definition 只依赖
自己的 `AgentToolCatalogPort`；Runtime 通过统一 `AgentTool` 调用 MCP Adapter；JSON-RPC、HTTP
会话和凭据解析留在 infrastructure。

```text
Definition publish
  -> AgentToolCatalogPort
  -> McpCatalogService (审核/发布快照)

PlatformAgentExecutor
  -> AgentToolRegistry
  -> McpAgentToolAdapter
  -> McpClientPort
  -> StreamableHttpMcpClient
  -> MCP Server
```

## 数据模型

- `mcp_connector`：租户逻辑连接器、名称和状态。
- `mcp_connector_version`：端点、协议版本、凭据引用、超时和不可变配置。
- `mcp_tool_catalog_version`：ConnectorVersion 的一次发现结果、状态和内容指纹。
- `mcp_tool_snapshot`：工具名、描述、输入 Schema、只读风险、Schema 指纹。
- `agent_version_mcp_tool_binding`：AgentVersion 到 ToolSnapshot 的精确绑定。

发布状态只允许 `DRAFT -> REVIEWED -> PUBLISHED`，运行时只读取 PUBLISHED 快照。删除或
重新发现不会修改历史快照。

## 端口

- `McpClientPort`：initialize、initialized、listTools、callTool。
- `McpCatalogRepository`：Connector、Version、Catalog、Snapshot 的持久化。
- `McpCredentialResolver`：只返回运行时使用的秘密，不进入 DTO、模型请求和日志。
- `McpEndpointPolicy`：HTTPS、端口、解析地址和响应上限校验。

`McpAgentToolAdapter` 实现现有 `AgentTool`，通过不可变 ToolSnapshot 查找连接器版本并调用
`McpClientPort`。它不拥有重试、幂等或 AgentRun 状态机；这些仍由 Registry/Worker 管理。

## 运行时语义

1. AgentVersion 发布时检查绑定快照存在、状态为 PUBLISHED、风险为 READ_ONLY、Schema 有效。
2. Registry 计算有效工具集合并执行本地参数 Schema 校验。
3. Adapter 根据快照的 ConnectorVersion 调用 `tools/call`。
4. MCP 返回的文本、结构化内容和 `isError` 被转换为受限 `AgentTool.Result` 或类型化失败。
5. 现有 PlatformAgentExecutor 将结果交回 Provider，继续既有 Step/Checkpoint/Flowable 链路。

远程调用不能包在数据库事务中；超时后只读工具按 Registry 现有策略处理，未知结果不会被伪装成成功。

## 安全与兼容

- 保存阶段只接受 HTTPS Streamable HTTP；禁止 URL 用户信息、任意 Header 注入和本地进程命令。
- MCP Server 返回的描述和结果都是不可信数据；不会改变系统 Prompt、工具集合或权限。
- HTTP 客户端必须有连接、请求、响应和最大字节数限制。
- 首期实现协议最小子集，未知 JSON-RPC 错误和能力声明转换为稳定失败码。

## 迁移策略

新增版本迁移，不修改已执行迁移。所有新表使用租户列、索引、外键和 RLS；旧 AgentVersion
默认没有 MCP 绑定，行为保持不变。回滚通过停用新 Connector/ToolSnapshot 和回滚应用版本，
不删除历史运行审计。

## 被拒绝的替代方案

- 让模型直接访问 MCP URL：绕过租户和工具治理，拒绝。
- 运行时 tools/list 后立即向模型暴露：不可审计且改变已发布语义，拒绝。
- 在 AgentToolRegistry 外新增一套 MCP 幂等：导致双重状态机，拒绝。
