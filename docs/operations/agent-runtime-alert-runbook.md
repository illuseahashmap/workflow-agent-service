# Agent Runtime 告警处置手册

本手册对应 `deploy/prometheus/workflow-agent-alerts.yml`。指标只包含平台级低基数维度；具体租户、Run、Attempt 和 Trace 应通过 Agent 中心的运行态势与运行详情定位。

## 通用顺序

1. 查看 `/actuator/health`，确认数据库、Redis 和应用实例健康。
2. 在 Agent 中心打开“运行记录”，先看排队、运行、暂停、待人工和 24 小时失败摘要。
3. 按错误分类和 Trace ID 定位详情，确认最近 Attempt、Step、Checkpoint 与恢复决策。
4. 只执行当前状态允许的动作：暂停、恢复、取消或修复后重试；所有动作都会进入操作账本。
5. 不要直接修改 `agent_run`、Attempt 或 Checkpoint 表。

## QueueStalled

- 检查 Worker 是否仍在轮询，以及线程池和租户并发上限是否饱和。
- 检查 Provider 延迟、超时和限流。不要先盲目扩大线程池，否则可能放大外部依赖压力。
- 若需要维护，先暂停相关 Run；已在执行的外部调用会在下一个安全边界停下。

## ExpiredLeases / RecoveryBacklog / LeaseLossBurst

- 确认 Recovery 调度仍运行，并观察过期租约数是否在两个恢复周期内归零。
- 检查实例 GC、数据库连接池、网络抖动和续租调度延迟。
- 旧 Worker 已由租约与 Attempt fencing 阻止写入；不要重复手工创建 Attempt。

## CompletionDeadLetters

- 在平台运维死信接口查看最后错误与关联流程实例。
- 可确认故障已消除时执行 replay；确认业务上无需推进时才执行 ignore，并填写原因。
- 未处置前 Flowable 会保留在原 Agent 节点，这是保护性行为。

## PauseRequestStalled

- 协作式暂停不会杀死正在进行的模型或工具 I/O；先核对调用超时是否合理。
- 若业务要求立即终止，使用取消操作。取消后迟到 Worker 不能覆盖新状态，但外部只读调用可能仍自然返回。
- 写工具必须依赖工具幂等与 fencing，不能把“取消线程”当作副作用回滚机制。

## RetryBurst

- 按错误分类区分 Provider 瞬态、配置、输入契约与输出策略错误。
- 配置类和契约类问题必须修正后由人工重试；不要提高自动重试次数掩盖永久故障。
