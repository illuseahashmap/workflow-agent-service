# Agent Runtime 告警处置手册

更新时间：2026-10-12

本手册对应 `deploy/prometheus/workflow-agent-alerts.yml`。指标只包含平台级低基数维度；具体租户、Run、Attempt 和 Trace 应通过 Agent 中心的运行态势与运行详情定位。

本地接线使用 `deploy/docker-compose.observability.yml`。先把具有
`ROLE_PLATFORM_ADMIN` 的短期 Bearer Token 写入工作区外的只读文件，再设置
`WORKFLOW_PROMETHEUS_TOKEN_FILE` 为该绝对路径。Token 不进入 Git；生产环境必须由
密钥管理系统轮换。仓库提供 `deploy/alertmanager/alertmanager.production.yml` 和
`deploy/docker-compose.observability.production.yml`；生产启动时必须通过
`WORKFLOW_ALERTMANAGER_WEBHOOK_URL_FILE` 挂载组织批准的 Webhook Secret，不能使用本地空 Receiver。

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

## HeartbeatSchedulingDelay / HeartbeatQueueBacklog / HeartbeatCapacityNearLimit

- 同时观察续租调度延迟 P95、续租数据库耗时和队列深度，先区分调度器饱和与数据库变慢。
- 调度器是实例级有界共享池，不为每个 Run 创建独立线程；调整
  `WORKFLOW_AGENT_WORKER_HEARTBEAT_POOL_SIZE` 前先确认数据库连接池仍有余量。
- 活动注册超过容量 90% 时先降低 Agent Run 接纳或扩实例；容量通过
  `WORKFLOW_AGENT_WORKER_HEARTBEAT_MAXIMUM_TRACKED_RUNS` 设置，不能把调大上限当作数据库变慢的修复。
- 若调度延迟逼近 `lease-seconds / 3`，先降低新 Run 接纳或扩实例，再调查 GC、CPU 和数据库锁等待。

## CompletionDeadLetters

- 正常完成事件在事务提交后会立即唤醒本机 Dispatcher；`WORKFLOW_AGENT_COMPLETION_SWEEP_INTERVAL_MS`
  定时扫描负责漏唤醒、到期重试和过期租约。XXL-Job 可使用 SERVICE Token 调用
  `POST /internal/jobs/outbox/agent-completions/recover` 作为额外恢复触发器，但不能替代数据库状态机。
- 事件由有界虚拟线程执行，并发上限由 `WORKFLOW_AGENT_COMPLETION_MAX_CONCURRENCY` 控制；同时观察
  `workflow_agent_outbox_completion_active` 与 `workflow_agent_outbox_completion_capacity`，不得仅扩大虚拟线程数而忽略数据库连接池和 Flowable 容量。
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
