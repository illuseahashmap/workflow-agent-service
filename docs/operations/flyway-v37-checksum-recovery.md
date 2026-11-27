# V37 checksum 兼容处理手册

更新时间：2026-08-25

## 背景

仓库中的 V37 必须保持不可变。当前仓库使用的是原始 V37，历史修复已放入 V38。

历史上曾短暂出现过一个修改版 V37：它在原始 V37 的基础上增加了“无租约 RUNNING 工具记录标记为 UNKNOWN”的 SQL。该版本与当前 V37 的 Flyway checksum 不同。

因此存在两类数据库：

1. 只执行过原始 V37：直接执行 V38，不需要 repair。
2. 已执行过修改版 V37：升级前会出现 `FlywayValidateException`，必须由数据库管理员确认后执行一次 `repair`，不能修改 V37 或跳过校验。

## 先确认，不要直接 repair

停止应用，先备份数据库，并查询 V37 的实际记录：

```sql
SELECT installed_rank, version, description, checksum, installed_on, success
FROM flyway_schema_history
WHERE version = '37';
```

同时确认 V37 的结构性变更是否已经存在：

```sql
SELECT column_name
FROM information_schema.columns
WHERE table_name = 'agent_tool_execution_audit'
  AND column_name IN ('claim_owner', 'lease_expires_at', 'fencing_token')
ORDER BY column_name;

SELECT indexname
FROM pg_indexes
WHERE tablename = 'agent_tool_execution_audit'
  AND indexname = 'idx_agent_tool_audit_claim_recovery';
```

如果 V37 记录不存在、`success = false`、结构变更不完整，先恢复数据库或由 DBA 单独处理，不得执行本文的 repair 流程。

## 已执行原始 V37

使用与应用完全相同的 datasource、schema 和 migration locations 执行：

```text
flyway info
flyway migrate
```

预期结果是 V38 正常执行，并将升级前遗留的、无租约的 `RUNNING` 工具审计记录标记为 `UNKNOWN`。

## 已执行修改版 V37

只有在确认以下事实后才能 repair：

- 修改版 V37 已成功提交，三列和恢复索引均存在；
- 业务接受 V38 对历史无租约记录执行幂等的 UNKNOWN 处置；
- 当前数据库备份已完成，并且 repair 使用的是当前仓库版本的 V37/V38 文件。

确认后，使用与应用相同的配置执行：

```text
flyway validate
flyway repair
flyway migrate
flyway validate
```

`repair` 的作用是把数据库中已执行迁移的 checksum 更新为当前迁移文件的 checksum；它不会重新执行 V37，也不会替代备份、结构检查或业务确认。随后只由 V38 执行历史记录恢复。

## 验收

```sql
SELECT version, success
FROM flyway_schema_history
WHERE version IN ('37', '38')
ORDER BY installed_rank;

SELECT status, error_code, COUNT(*)
FROM agent_tool_execution_audit
WHERE status = 'UNKNOWN'
  AND error_code = 'AGENT_TOOL_LEGACY_CLAIM_UNKNOWN'
GROUP BY status, error_code;
```

应用启动日志中不得再出现 `FlywayValidateException`，且 V37 不得被重新执行。以后任何修复只能新增 V39、V40 等迁移，禁止再次修改 V37 或 V38。
