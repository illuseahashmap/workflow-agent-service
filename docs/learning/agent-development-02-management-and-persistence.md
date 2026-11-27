# Agent 开发第二课：管理面、版本与持久化

## 本课完成了什么

这一课把第一课的领域状态机接到了可管理、可查询、可审计的基础设施上：

```text
前端 Agent 中心
├── Agent 定义与不可变版本
├── Provider 与加密凭证
└── 运行记录与执行账本详情
    ├── Attempt
    ├── Step
    ├── Checkpoint
    └── State History

REST API → Application Service → Domain/Port → JDBC Repository → PostgreSQL
```

本课聚焦管理面和持久化边界；真实模型调用由第三课的 Worker 和 Provider Adapter 承担。当前代码已经具备 Mock/OpenAI Compatible Provider，课程仍然只讲本课负责的配置、版本和审计问题。

## 1. 为什么 AgentDefinition 和 AgentVersion 必须分开

`AgentDefinition` 保存稳定身份，例如编码、名称、说明和是否启用；`AgentDefinitionVersion` 保存会影响执行结果的配置，例如 Provider、模型、系统提示词、超时、失败策略和输出 Schema。

版本发布后不可修改。原因是：如果历史运行只记录 Agent ID，而同一条配置可以被覆盖，就无法证明一次流程当时究竟使用了什么 Prompt 和模型。

正确的变更过程是：

```text
版本 1（已发布） → 创建版本 2（草稿） → 编辑和验证 → 发布版本 2
```

同一个 Agent 同一时刻最多保留一个草稿，避免两个草稿并行修改后产生含义不明确的发布顺序。

## 2. 为什么 Provider 属于租户

不同租户可能使用不同的模型平台、网关地址、模型名称、额度和密钥。`agent_provider` 因此带有 `tenant_code`，Agent 版本只能绑定同一租户的 Provider。

数据库使用复合外键而不只依赖 Service 校验：

```text
(provider_id, tenant_code)
    → agent_provider(id, tenant_code)
```

这叫“纵深防御”：应用层负责友好的错误提示，数据库负责在并发、脚本或未来代码出现缺陷时守住最终边界。

## 3. API Key 如何保存

浏览器只提交一次明文密钥，后端使用 AES-256-GCM 加密后保存：

- 密钥材料来自环境变量 `WORKFLOW_AGENT_CREDENTIAL_MASTER_KEY_BASE64`；
- 每次加密使用随机 IV；
- AAD 绑定 `tenantCode + providerId`，密文不能被复制到另一个租户或 Provider 使用；
- 查询 API 只返回“是否已配置”和末四位提示，不返回明文或密文；
- 编辑时留空表示保留原凭证。

生产环境的主密钥应由 Secret Manager 或 KMS 注入并保持稳定，不能每次启动随机生成。主密钥轮换需要专门的密文版本和重加密流程，不能直接替换环境变量。

## 4. 运行详情为什么分成四类

一次 `AgentRun` 是业务总账，详情中的结构承担不同职责：

- `Attempt`：一次领取和执行尝试，重试会新建 Attempt；
- `Step`：检索、模型调用、输出校验等可观测步骤；
- `Checkpoint`：恢复执行所需的快照；
- `State History`：状态变化的不可变审计事实。

数据库约束保证 Step、Checkpoint 和状态历史引用的 Attempt 必须属于同一个 Run。仅保证“同租户”是不够的，否则两个并发运行可能错误串联。

## 5. DDD 分层如何阅读

以 Agent 定义为例：

```text
interfaces/rest
  只负责 HTTP、参数绑定和权限
        ↓
application
  编排用例、读取当前租户、控制事务
        ↓
domain
  表达版本发布规则和业务不变量
        ↓ port
infrastructure/persistence
  使用 JDBC 实现仓储
```

领域层不依赖 Spring、JDBC、Controller 或 Flowable，架构测试会自动阻止依赖方向倒置。这样未来将 JDBC 换为其他持久化方式，或把 Agent Worker 拆成独立部署单元时，领域规则仍然可以复用。

## 6. 前端页面现在能做什么

租户管理员或拥有对应权限的角色可以在“Agent 中心”完成：

1. 新建和编辑 Agent 定义；
2. 创建草稿、选择 Provider、配置 Prompt/Schema/策略并发布；
3. 配置 Mock 或 OpenAI 兼容 Provider；
4. 查看运行列表，以及 Attempt、Step、Checkpoint、状态历史详情。

运行记录当前为空是正常现象，因为 Worker 和 Flowable Agent 节点还未接入。页面先围绕稳定 API 构建，后续执行功能产生的数据会自然显示，不需要重做管理页面。

## 7. 下一课：真正调用模型

下一阶段建议按以下顺序实现：

1. 定义 `ModelProviderPort`，让应用层不依赖 LangChain4j 或任何厂商 SDK；
2. 实现 `MockModelProvider`，先验证成功、失败、超时和重试闭环；
3. 实现 Worker 的领取、租约续期、Attempt/Step 写入和状态机调用；
4. 实现 OpenAI-Compatible Adapter，再决定内部是否采用 LangChain4j；
5. 增加输入限制、输出 JSON Schema 校验、日志脱敏和 Provider 并发限制；
6. 最后接入 Flowable Agent Service Task 和可靠事件。

先做 Mock 的价值在于：如果直接接真实模型，网络、密钥、模型输出和业务状态机会同时变化，很难判断错误来自哪一层。

## 8. 自测题

1. 为什么修改已发布 Prompt 必须创建新版本？
2. 为什么 API 不能把加密后的密文返回给前端？
3. 为什么 Provider Adapter 应实现应用层端口，而不是被领域对象直接调用？
4. 为什么 Step 的 `attempt_id` 和 `agent_run_id` 必须由复合外键共同约束？
5. 为什么有了 Agent 管理页面仍不能说明系统已经具备模型执行能力？
