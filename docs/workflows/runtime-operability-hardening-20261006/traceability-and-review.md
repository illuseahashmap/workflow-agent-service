# Traceability and review

更新时间：2026-10-06

## 验收映射

| 验收项 | 结果 | 证据 |
| --- | --- | --- |
| AC-01 Runtime 健康摘要 | PASS | 租户范围 overview 返回状态分布、最老排队时间、过期租约和人工介入数量；应用服务与仓储单测覆盖。 |
| AC-02 指标、告警与 Runbook | PASS（结构与单测） | 低基数 Micrometer 指标、Prometheus 规则和 `docs/operations/agent-runtime-alert-runbook.md` 已提交；Alertmanager 与容器告警演练仍是发布验收项。 |
| AC-03 暂停 | PASS | QUEUED 立即暂停；RUNNING 在安全边界协作式暂停；重复命令幂等。单元测试和浏览器主路径均验证。 |
| AC-04 恢复 | PASS | PAUSED 恢复为 QUEUED，保留 Checkpoint 并由新 Attempt 继续；浏览器验证后端重启后重新领取。 |
| AC-05 操作账本与权限 | PASS | 暂停、恢复、取消和重试写入状态历史与操作账本，并复用租户上下文和运维权限；PostgreSQL、RLS 与 HTTP 安全基础设施套件已在 Testcontainers 环境通过。 |
| AC-06 前端状态化操作 | PASS | 运行详情只展示当前状态允许的暂停、恢复、取消或重试动作，健康摘要与失败诊断可见。 |
| AC-07 自动化与浏览器验证 | PASS | 后端 `mvn verify`、OpenAPI 门禁、前端 `pnpm check` 全部通过；浏览器验证暂停、恢复、Worker 重启接管和输入契约失败诊断。 |
| AC-08 不虚报容器验收 | PASS | 本机 Docker Engine 29 已恢复，docker-java API 兼容配置收敛到父构建；全量 271 个测试为 0 跳过，5 个必需基础设施套件通过门禁。真实强杀、租约过期和多实例故障注入仍明确保留。 |

## 本轮根因修复

1. Flyway 暂停迁移最初与既有 V49 重号。迁移改为 V50，并新增全量迁移版本唯一性测试，防止以后再次依赖人工发现。
2. 本地数据库表所有者与运行账号不一致会阻止 Flyway 升级。应用增加独立迁移凭据配置，同时保留默认复用数据源的兼容行为；最小权限账号初始化模板仍作为 P1 部署事项跟踪。
3. 前端确认辅助函数过去会吞掉组件渲染和编程异常，导致操作“看似没反应”。现在只将用户主动取消/关闭转为取消结果，其余异常继续抛出，并增加单元测试。
4. 实际 Worker 领取测试使用了不符合 Agent JSON Schema 的纯文本输入。运行按预期失败，并在前端完整展示输入契约分类、错误码、Attempt、Step、恢复决策和修复入口；该失败不是暂停/恢复链路故障。

## 已执行门禁

- 后端：`mvn verify`，9 个 Reactor 模块全部成功；Checkstyle、SpotBugs、JaCoCo 通过。
- 容器：PostgreSQL 17、Redis 8、Flowable、Flyway、RLS 和 HTTP 安全相关测试实际执行；81 份 Surefire 报告合计 271 个测试，0 失败、0 错误、0 跳过；`scripts/check-integration-test-results.mjs` 确认 5 个必需基础设施套件齐全。
- OpenAPI：Redocly lint/bundle、路由覆盖、成功响应模型和相对 `main` 的兼容性检查通过；保留 59 条既有 warning。
- 前端：格式、ESLint、TypeScript、39 个单元测试、生产构建、包体预算和 14 个 Playwright 场景通过。
- 浏览器：`QUEUED -> PAUSED -> QUEUED -> Worker 重启后领取`；随后验证终态失败的可定位、可处置展示。

## 未完成的生产验收

- Worker 真实强杀、租约过期、运行中外部调用边界暂停和跨实例压力。
- Prometheus 到 Alertmanager 的真实触发、通知和处置回放。

上述项目不得由 Mock、单机浏览器或编译成功替代。
