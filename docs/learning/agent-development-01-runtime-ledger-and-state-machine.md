# Agent 开发第一课：运行账本与状态机

## 本课目标

完成本课后，需要能够回答：

1. `AgentRun` 为什么不是一次普通的模型 HTTP 请求？
2. 为什么所有状态变化必须经过状态机？
3. Worker 重复执行或迟到返回时，系统如何避免破坏流程？

对应代码位于：

```text
agent-engine/src/main/java/io/github/illuseahashmap/agent/runtime/domain
```

## 1. AgentRun 是什么

`AgentRun` 表示一次有业务身份的 Agent 执行。即使应用重启、Worker 更换或者模型调用重试，它仍然是同一次业务运行。

可以把它理解成一张物流总单：

- `agentVersionId` 表示使用哪一版 Agent 配置；
- `idempotencyKey` 防止同一个流程节点重复创建运行；
- `currentAttemptId` 表示当前由哪一次尝试执行；
- `deadlineAt` 表示整次运行最晚何时完成；
- `status` 表示当前生命周期状态；
- `stateHistory` 保存每次变化的审计记录。

模型请求只是这张总单中的一个步骤。未来一次 `AgentRun` 可以包含检索、模型调用、结果校验等多个步骤。

## 2. 为什么还要有 Attempt、Step 和 Checkpoint

后续会在总账下面增加三类明细：

```text
AgentRun：一次业务运行
├── Attempt：一次执行尝试，失败重试会产生新的 Attempt
│   ├── Step：检索、模型调用、结果校验等可审计步骤
│   └── Checkpoint：Worker 重启后可以恢复的位置
└── Result：最终对工作流有意义的结果
```

它们不能合并：如果只保留最终状态，就无法解释调用过几次模型、在哪一步失败、重启后从哪里恢复。

## 3. 状态机解决什么问题

如果任意 Service 都可以执行 `run.setStatus(...)`，就可能出现：

- 尚未领取租约就直接成功；
- 不可重试的错误被重新执行；
- 旧 Attempt 的迟到结果覆盖新 Attempt；
- 已取消的任务又被完成；
- 状态变化没有原因、操作者和 Trace ID。

因此 `AgentRunStateMachine` 是唯一的生命周期入口。MVP 状态如下：

```text
QUEUED --startLease--> RUNNING --markSucceeded--> SUCCEEDED
                          |------markFailed-----> FAILED
                          |------markTimedOut---> TIMED_OUT
                          |------cancel---------> CANCELLED
                          `------retry----------> QUEUED

QUEUED ------------------cancel----------------> CANCELLED
```

`SUCCEEDED`、`FAILED`、`TIMED_OUT`、`CANCELLED` 都是终态。完成类命令再次到达时保持幂等：不改变状态，也不重复追加历史。

## 4. 为什么需要 Worker 租约

生产环境可能同时运行多个 Worker。它们会竞争数据库里的待执行任务，领取成功的 Worker 获得一段有期限的租约：

```text
attemptId + owner + expiresAt
```

状态机只允许有效租约启动运行，并要求租约不能超过整次运行的 Deadline。Worker 宕机后租约会过期，其他 Worker 才能安全接管。

租约不是数据库锁的替代品。领取时仍需要数据库事务保证同一时刻只有一个 Worker 获得所有权；状态机负责验证领取结果在业务上是否合法。

## 5. 为什么失败还要分类

`AgentFailureDisposition` 把失败分为：

- `RETRYABLE`：暂时故障，例如 Provider 短暂不可用；
- `NON_RETRYABLE`：确定无法通过重试解决，例如输出违反 Schema；
- `RETRIES_EXHAUSTED`：原本可重试，但次数已经耗尽。

状态机不允许把 `RETRYABLE` 直接记成最终失败。调用方必须选择重新排队，或者在策略耗尽后以 `RETRIES_EXHAUSTED` 结束。这可以避免不同 Worker 各自解释重试规则。

## 6. 状态历史记录了什么

每次真实变化都会生成不可变的 `AgentRunStateTransition`：

```text
tenantCode / agentRunId / attemptId
oldStatus / newStatus / reasonCode
operatorType / operatorId / traceId / createdAt
```

其中 `reasonCode` 用于程序判断，不能只记录自然语言；`traceId` 用于关联 API、事件、Worker 和 Provider 日志。

## 7. 如何阅读本课代码

建议按以下顺序阅读：

1. `AgentRunStatus`：先理解生命周期边界。
2. `AgentRun`：查看总账保存了哪些事实。
3. `AgentRunTransitionContext`：查看审计需要哪些上下文。
4. `AgentRunStateMachine`：逐个阅读命令及其前置条件。
5. `AgentRunStateMachineTest`：通过业务场景验证理解。
6. `AgentArchitectureTest`：理解为什么领域层不能依赖框架。

## 8. 本课测试覆盖

当前单元测试验证：

- 有效和过期租约；
- 成功前的步骤和结果策略校验；
- 可重试、不可重试和耗尽失败；
- Provider 超时和总 Deadline 超时；
- 排队中与运行中的取消；
- 重试重新排队；
- 旧 Attempt 迟到结果；
- 终态命令幂等；
- 审计时间不能倒退；
- 状态历史不能被外部修改；
- 领域层不依赖 Spring、Flowable、JDBC 和外层代码。

## 9. 自测题

1. Provider 返回 HTTP 503 时，为什么不能立即把 `AgentRun` 标记为 `FAILED`？
2. `AgentRun` 已经重试到 Attempt 2，Attempt 1 的完成事件到达时应如何处理？
3. 为什么租约过期后不能直接复用原 Attempt？
4. 为什么终态幂等不等于可以忽略 Inbox 去重？

下一课将把领域对象持久化到 PostgreSQL，并实现租户化 Repository、Attempt、Step、Checkpoint 和状态历史表。
