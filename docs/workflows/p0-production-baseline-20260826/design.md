# P0 生产基线收口设计

## 方案

在 `workflow-engine` 内增加流程操作审计查询端口和应用服务，由 JDBC 适配器执行租户边界内的参数化分页查询；在现有流程管理 Controller 增加只读查询入口。写入仍由既有 `WorkflowOperationAuditService` 在状态变更事务中完成。

查询条件仅允许结构化字段：事件类型、流程实例 ID、Trace ID、发生时间区间和页码；不支持原始 SQL 或任意过滤表达式。审计结果只返回已定义的操作证据字段。

权限使用 `PLATFORM_ADMIN` 或 `workflow:audit:read`，租户上下文来自服务端，不信任请求中的租户字段。现有 PostgreSQL RLS 继续作为纵深防御。

## P0-1 处理

不重复建设已有 Testcontainers。保留 `check-integration-test-results.mjs` 作为 CI 门禁，执行验证时记录 Docker 不可用为环境阻塞，而不是降低测试要求。

## 失败与兼容

- 非法分页、空白过滤条件和反向时间范围返回标准 BAD_REQUEST。
- 无记录返回空分页结果，不返回 404。
- 查询失败沿用全局错误响应。
- 新 API 为新增路由，不改变现有路由响应。
