# MCP 短期实施方案与生产闭环

更新时间：2026-08-25
状态：MCP-1/MCP-2 后端切片已实现，待真实容器化集成与生产出站治理验收

本文定义 MCP 从连接器配置、工具发现到 Agent 运行时调用的近期闭环。MCP 是外部工具的
标准协议适配器，不是新的 Agent 执行引擎，也不能绕过现有 Tool Registry、租户治理、
AgentVersion、AgentRun 和人工确认边界。

当前完成状态仍以[下一步计划](../status.md)为准，长期原则遵循
[Agent 协作架构设计](agent-collaboration-design.md)。

## 1. 当前基线与缺口

当前代码已经具备：

- Provider 原生 Tool Calling 和受控文本工具协议；
- `AgentTool`、`AgentToolRegistry` 和本地只读工具；
- 租户工具授权、输入 Schema 校验、幂等键、输出大小限制和执行审计；
- AgentRun/Attempt/Step、超时、重试、Checkpoint、Recovery 和 Flowable 恢复。

本次已实现以下后端切片：

- Connector/ConnectorVersion、目录版本、工具快照和 AgentVersion 绑定表，并启用租户 RLS；
- HTTPS Streamable HTTP 的 initialize、notifications/initialized、分页 tools/list 和 tools/call；
- 目录 Schema 校验、指纹、审核发布，以及 MCP 工具注册到既有 Registry；
- `McpAgentToolAdapter` 通过现有 Registry 接入租户授权、版本冻结、Schema、幂等和审计链路。

仍未完成真正生产级闭环的部分：

- 没有真实 MCP Server 容器化集成、Flowable 继续运行和 Worker 重启故障测试；
- 尚未完成 SSRF/DNS 重绑定、私网/元数据地址拒绝、限流、熔断和租户公平配额；
- 当前每次调用重新建立 MCP 会话，尚无连接池/会话复用和凭据轮换；
- MCP 错误已经映射到 AgentFailure，但完整 MCP SDK/批量 JSON-RPC/SSE 生命周期兼容性仍需真实协议测试后确认；
- 尚未提供完整连接器管理前端，也不支持写工具、stdio 或运行时自动发现。

因此“Provider 支持 Tool Calling”和“存在 Tool Registry”不等于已经接入 MCP。

## 2. 稳定边界

调用方向固定为：

```text
ModelProvider Tool Calling
→ PlatformAgentExecutor
→ AgentToolRegistry
→ McpAgentToolAdapter
→ McpClientPort
→ MCP Server
```

- Provider 只负责让模型按结构化协议提出工具调用，不感知 MCP 地址和凭据。
- Agent Runtime 只依赖统一 `AgentTool` 契约，不直接处理 MCP JSON-RPC 或传输细节。
- Tool Registry 统一执行工具集合求交、授权、Schema、风险、幂等和审计。
- MCP Adapter 负责协议映射和错误归类，不建立第二套 Run、Attempt、Step 或 Recovery。
- MCP 会话不是事实存储；进程重启后可以重连，业务恢复仍以 PostgreSQL 账本为准。

近期不新增独立 Maven 模块。MCP 作为 `agent-engine` 的工具基础设施适配器实现；只有当
连接器种类和独立发布需求形成真实复杂度后，才评估拆分 `connector-engine`。

## 3. 配置与版本模型

至少预留以下概念：

| 模型 | 责任 |
| --- | --- |
| `McpConnector` | 租户拥有的逻辑连接器、启停状态和所有者 |
| `McpConnectorVersion` | 不可变传输配置、端点、协议版本、凭据引用和出站策略引用 |
| `McpToolCatalogVersion` | 一次发现并审核发布的工具目录快照 |
| `McpToolSnapshot` | 工具名、描述、输入 Schema、输出约束、风险和内容指纹 |
| `AgentToolSetVersion` | Agent 发布时选定的工具及精确目录版本 |
| `AgentToolInvocation` | Run/Attempt/Step 与连接器、目录、工具调用的审计关联 |

发布后的 AgentVersion 必须引用不可变 `AgentToolSetVersion`。运行时可见工具必须是以下集合
的交集，而不是“租户授权的全部工具”：

```text
平台注册工具
∩ 租户授权工具
∩ AgentVersion 冻结工具
∩ BPMN 节点允许的工具子集
∩ 当前运行时安全策略
```

MCP Server 后续修改或删除工具时，不得静默改变已发布 AgentVersion。管理员必须重新发现、
生成新目录版本、执行兼容性检查并发布新的 AgentVersion。

## 4. 连接、发现与发布

首期只支持服务端可治理的 **Streamable HTTP**。不允许租户配置任意本地 `stdio` 命令，
避免远程代码执行、进程泄漏和多租户资源隔离问题。

管理闭环：

```text
创建 Connector 草稿
→ 校验端点与凭据引用
→ initialize / 协议版本与能力协商 / notifications/initialized
→ 分页 tools/list
→ 校验名称、Schema、数量和大小
→ 生成目录快照与指纹
→ 管理员审核并发布
→ AgentVersion 选择工具并发布
```

连接器只保存 `credentialRef`，不保存或返回明文密钥。连接测试和工具发现是管理操作，必须
校验租户、权限并写审计；运行时不得临时发现工具后直接暴露给模型。

客户端必须处理 Streamable HTTP 会话标识和服务器声明的能力。收到工具目录变更通知时，
只能触发重新发现并生成待审核的新目录版本，不能热替换运行中 AgentVersion 的工具快照。

## 5. 运行时调用

`McpAgentToolAdapter` 将 Registry 已批准的请求转换为 `tools/call`，并执行：

1. 使用快照 Schema 在本地校验参数，拒绝额外字段和超限输入。
2. 根据连接器版本解析凭据，通过受控出站客户端发起调用。
3. 应用连接、响应和总调用超时，不占用数据库事务等待远程响应。
4. 限制响应字节数、内容类型和可注入模型的字符/Token 数。
5. 将协议错误、`isError` 工具错误、超时、限流、认证失败和未知结果映射为类型化 `AgentFailure`。
6. 将工具输出标记为不可信外部内容，再交给现有 Agent Step 和结果策略。
7. 审计工具快照指纹、参数摘要、延迟、结果状态和 Provider Trace，不记录凭据或敏感全文。

只读调用可以按冻结策略重试。写调用发生超时后可能已经在外部成功，不能盲目重试；必须
具备外部幂等键、结果查询或补偿能力，否则进入 `UNKNOWN_OUTCOME` 并转人工处置。

## 6. 安全与资源治理

首期必须同时落地：

- 端点只允许 `https`，经过域名/网段白名单、DNS 重绑定防护和统一出站策略；
- 默认拒绝环回、链路本地、云元数据地址和未授权私网地址，防止 SSRF；
- 凭据按租户隔离并支持轮换，模型、Prompt、日志和前端均不可读取；
- 工具描述和结果都视为不可信输入，不能覆盖系统指令或扩大工具权限；
- 按租户和 Connector 设置并发、速率、响应大小和超时上限；
- 健康检查、失败计数和熔断只影响可用性，不改变已发布工具语义；
- Connector 停用后禁止新调用，运行中的调用按固定策略结束并保留审计。

风险分级预留 `READ_ONLY`、`REVERSIBLE_WRITE`、`HIGH_RISK_WRITE`。MCP-1 至 MCP-3 只允许
`READ_ONLY`；写工具必须等人工确认和未知结果处置能力完成后再开放。

## 7. 分阶段任务

### MCP-1：连接器与目录版本

- 建立 Connector、ConnectorVersion、CatalogVersion 和 ToolSnapshot 模型及迁移。
- 实现 Streamable HTTP、完整初始化生命周期、会话处理、分页 `tools/list` 和连接测试。
- 增加工具目录审核发布、Schema 指纹和新旧目录兼容性检查。
- 建立 AgentToolSetVersion，并让 AgentVersion 绑定精确工具快照。

验收：服务重启后仍可按数据库配置重连；MCP Server 改变 Schema 不影响已发布版本，重新
发布前能够明确报告兼容或破坏性变化。

### MCP-2：只读调用纵向闭环

- 实现 `McpClientPort` 和 `McpAgentToolAdapter` 的 `tools/call`。
- 将 MCP 工具接入 Registry 的五层集合求交、Schema、幂等、超时和审计。
- 用一个真实测试 MCP Server 跑通模型选择工具、远程调用、结果回注和 Agent 完成。
- 未授权租户、未绑定工具、伪造工具名和超限响应必须被拒绝。

验收：形成“配置 Connector → 发现并发布目录 → AgentVersion 绑定 → 模型发起 Tool Call →
Registry 校验 → MCP 调用 → 结果回注 → AgentRun 完成”的可重复集成测试。

### MCP-3：可靠性与安全闭环

- 完成 SSRF/DNS 重绑定负面测试、凭据轮换、限流、熔断和租户公平配额。
- 覆盖超时、断连、重复请求、协议不兼容、工具删除、Schema 漂移和服务重启。
- 审计关联 tenant、AgentVersion、Run/Attempt/Step、ConnectorVersion、CatalogVersion、
  ToolSnapshot、幂等键和 Trace ID。

验收：故障不会误判 Agent 成功、不会越权调用、不会因重启丢失业务状态，并能由现有
Recovery 或人工命令继续处理。

### MCP-4：受治理写工具

- 接入人工确认策略和前端审批交互。
- 要求外部幂等能力声明、结果查询或补偿契约。
- 对未知结果、拒绝、撤回和补偿建立明确终态及审计。
- 高风险写操作默认逐次确认，禁止仅凭 Prompt 授权。

验收：重复投递不产生重复副作用；超时未知结果不会自动当作失败重试；人工可以确认、
拒绝、接管并追溯整个操作。

## 8. 完成定义

完成 MCP-1 至 MCP-3 后，只能称为“生产可用的只读 MCP 闭环”；完成 MCP-4 及其故障测试后，
才可以称为“受治理的读写 MCP 闭环”。仅实现 SDK 调用、`tools/list` 或一次 `tools/call` 不算
完成。

近期明确不做：

- MCP Server 托管、插件市场和任意租户代码执行；
- 租户提供的任意 `stdio` 命令或本地进程；
- 未审核工具的运行时自动发现与立即使用；
- 无上限工具输出、跨租户目录共享和明文凭据；
- 在人工确认与未知结果处理前开放写工具。
