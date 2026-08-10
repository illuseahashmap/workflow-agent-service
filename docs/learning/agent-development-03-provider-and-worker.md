# Agent 开发第三课：Provider 端口、Worker 与真实模型调用

## 本课完成了什么

这一课没有增加一个绕过运行账本的“测试连接”接口，而是实现了第一条可复用的正式执行链路：

```text
前端手动测试
→ 创建 AgentRun(QUEUED, MANUAL_TEST)
→ Worker 使用 SKIP LOCKED 领取
→ 创建 Attempt 与 MODEL_CALL Step
→ ModelProviderPort
→ Mock / OpenAI Compatible Adapter
→ 保存模型调用审计
→ AgentRunStateMachine 进入成功、重试或失败
```

以后 Flowable Agent 节点只需要换一个运行触发入口，后面的 Worker、Provider、Attempt、Step 和状态机都可以继续复用。

## 1. 为什么手动测试也必须创建 AgentRun

如果测试按钮由 Controller 直接调用模型，就会产生第二套没有租约、重试、审计和租户边界的执行路径。它可能“测试成功”，却不能证明正式流程能够运行。

本实现把手动测试标记为 `MANUAL_TEST`，但仍然创建正常的运行账本。测试产生的模型费用、错误分类和 Token 用量都可以查询。

## 2. Provider 端口解决什么问题

应用层只依赖 `ModelProviderPort`：

```text
ModelProviderRequest
├── model
├── systemPrompt
├── userInput
├── timeout
└── traceId

ModelProviderResponse
├── content
├── actualModel
├── providerRequestId
├── tokenUsage
└── latency
```

OpenAI 的 URL、Bearer Header、请求 JSON、HTTP 状态码和响应解析全部留在基础设施 Adapter。领域模型不知道 HTTP，也不依赖任何模型 SDK。

当前 `OPENAI_COMPATIBLE` Adapter 支持两种常见入口：Base URL 以 `/responses` 结尾时使用 Responses API 的 `instructions + input` 协议；以 `/chat/completions` 结尾或只填写 API 根地址时使用 Chat Completions 的 `messages` 协议。协议差异只存在于基础设施层，不会扩散到应用服务和领域对象。

当前没有引入 LangChain4j。单次模型调用用 JDK HTTP Client 足够清晰；等到工具调用、结构化输出兼容、流式响应和多 Provider 能力协商明显增多时，再评估框架收益。

## 3. API Key 为什么只在 Worker 内解密

管理 API 查询 Provider 时只返回 `credentialConfigured` 和提示信息。Worker 真正执行时，`AgentCredentialResolver` 根据当前租户和 Provider 读取密文，再使用 AAD 绑定的 AES-GCM 解密。

明文密钥只在一次 Provider 调用的内存生命周期中出现，不进入响应 DTO、普通日志或运行快照。

## 4. Worker 如何避免并发重复领取

Worker 在数据库事务内执行：

```sql
SELECT ...
FROM agent_run
WHERE status = 'QUEUED'
  AND available_at <= now()
  AND deadline_at > now()
FOR UPDATE SKIP LOCKED
LIMIT 1;
```

领取后在同一事务中创建新的 Attempt 和 Step，并通过 `AgentRunStateMachine.startLease` 将运行推进到 `RUNNING`。多个应用实例可以并发轮询，但同一行只会被一个事务领取。

完成更新还会校验 `current_attempt_id`。即使将来增加租约过期接管，旧 Attempt 的迟到结果也不能覆盖新 Attempt。

## 5. 为什么 HTTP 503 不直接结束整个运行

Adapter 将错误分类为：

- `PERMANENT`：认证失败、地址错误、请求被拒绝；
- `RETRYABLE`：限流、503、网络暂时不可用；
- `TIMEOUT`：调用超时。

可重试错误先结束当前 Attempt，然后状态机把 AgentRun 放回 `QUEUED`，并设置退避后的 `available_at`。下一次领取一定创建新 Attempt。只有重试耗尽、不可重试错误或截止时间到达后才进入终态。

## 6. 模型调用审计保存什么

`agent_model_invocation` 保存：

- Provider、请求模型和实际模型；
- Provider Request ID 与 finish reason；
- 输入、输出、推理 Token；
- 总耗时、状态和错误分类。

它不保存 API Key，也不保存 Provider 原始完整响应。业务输入和最终输出放在 AgentRun 的受租户保护快照中，后续还要增加敏感字段过滤和保留期限。

## 7. 当前边界

本课已经具备真实模型调用能力，但完整 Agent MVP 仍未完成：

1. 尚未接入 Flowable Agent Service Task；
2. 尚未实现 Outbox/Inbox 可靠事件；
3. 尚未实现租约续期和过期运行接管；
4. 尚未执行 Output Schema 与 Guardrail 校验；
5. 尚未实现 Checkpoint 恢复和 Provider 并发配额。

这些功能应继续扩展现有运行链路，不能另建一套执行记录。

## 8. 自测题

1. 为什么手动测试不能由 Controller 直接调用 OpenAI Adapter？
2. 为什么可重试失败必须创建新 Attempt？
3. 为什么模型调用表不保存完整原始响应？
4. `SKIP LOCKED` 解决了什么问题，它又不能替代什么？
5. 为什么现在可以称为“具备模型调用能力”，但仍不能称为“Agent MVP 已完成”？

## 9. 如何验证真实 Provider

1. 在 Provider 配置中填写 Base URL、默认模型和凭据；
2. 创建 Agent 草稿版本，绑定 Provider，并发布该版本；
3. 在 Agent 定义列表点击“测试运行”，输入一段最小业务内容；
4. 页面会进入运行记录并轮询正式 AgentRun，而不是直接等待 Controller 调用模型；
5. 终态成功时检查输入输出、Attempt、模型调用、Step 和状态历史；失败时根据错误分类判断配置错误、永久错误或可重试错误。

本地真实验证中，Responses API 已完成一次 `QUEUED → RUNNING → SUCCEEDED` 执行，并记录实际模型、Token 用量和耗时。这说明 Provider 配置、凭据解密、Worker 领取、协议适配、状态机和查询界面已形成一条完整链路。
