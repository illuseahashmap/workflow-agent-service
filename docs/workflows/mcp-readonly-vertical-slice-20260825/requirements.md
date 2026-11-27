# MCP-1 + MCP-2 只读纵向闭环需求

## 目标

让现有 `PLATFORM_AGENT` 在不改变 AgentRun、Attempt、Step、Checkpoint、Flowable 和 Tool
Registry 边界的前提下，能够调用经过审核冻结的远程 MCP Streamable HTTP 只读工具。

## 本期范围

- Connector 草稿、不可变 ConnectorVersion、工具目录版本和工具快照。
- HTTPS Streamable HTTP MCP 客户端：`initialize`、`notifications/initialized`、`tools/list`、`tools/call`。
- 目录发现后必须审核发布，运行时禁止临时发现工具。
- AgentVersion 绑定精确的已发布工具快照。
- MCP 工具通过现有 `AgentToolRegistry` 执行，继续经过租户授权、Schema、幂等、超时、输出限制和审计。
- 一个确定性的测试 MCP Server，覆盖配置到 Flowable 继续运行的纵向集成路径。

## 非目标

- 不支持 stdio、本地进程、任意 HTTP 工具或 MCP Server 托管。
- 不支持写工具、人工确认 UI、插件市场和运行时自动发现。
- 不引入 LangChain4j 或新的 Agent 执行引擎。
- 不把 MCP 凭据、完整远程响应或未审核工具暴露给模型。

## 验收标准

- AC-01：仅 HTTPS Streamable HTTP Connector 可保存和连接；HTTP、stdio、无效端点被拒绝。
- AC-02：连接测试完成 initialize/initialized/tools-list，并持久化可审核目录；分页结果和工具 Schema 有界。
- AC-03：只有已发布目录中的工具可以绑定 AgentVersion；目录或 Schema 变化不会影响已发布版本。
- AC-04：运行时有效工具集合为平台注册工具、租户授权、AgentVersion 快照、BPMN 节点子集和安全策略的交集。
- AC-05：MCP Adapter 只接收 Registry 已校验的调用，正确映射 tools/call、参数、超时、协议错误和 isError。
- AC-06：未授权租户、未绑定工具、伪造工具名、Schema 不匹配和超限结果不会发出远程请求。
- AC-07：只读 MCP 调用结果回注模型，AgentRun 的 Step、Checkpoint、审计和 Flowable 完成事件链路保持完整。
- AC-08：MCP 超时、断连和协议错误不能误判成功；重复 Attempt 使用现有稳定幂等语义。
- AC-09：确定性 MCP Server 集成测试覆盖发现、审核、绑定、调用、拒绝和流程推进。

## 风险与未决项

- Streamable HTTP 的会话标识和 JSON-RPC 响应形态需按测试 Server 与协议版本兼容；未知能力必须拒绝或降级为明确失败。
- 真实生产环境的 DNS Rebinding、租户配额、熔断和凭据轮换属于 MCP-3，不在本期假装完成。
