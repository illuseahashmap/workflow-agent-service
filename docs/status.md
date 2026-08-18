# 下一步计划与当前状态

更新时间：2026-08-18

本文是“下一步计划”唯一入口。长期原则见[长期设计总览](architecture/long-term-design.md)，具体缺陷见[待修复问题](quality/known-issues.md)。

## 一、当前基线

### 平台与工作流

- 已完成模块化单体拆分：`auth-engine`、`workflow-engine`、`rules-engine`、`agent-engine`、`platform-migrations`、`workflow-boot`。
- 已具备注册登录、HttpOnly Cookie、CSRF、租户/角色/权限、流程版本、派单规则版本和基础审计。
- 已具备流程启动、审批、驳回、转办、终止、实例查询、参与人解析和流程图展示。
- 已建立 PostgreSQL RLS、租户上下文、Redis 锁和可靠兜底命令。

### Agent 首个纵向闭环

- 已完成 AgentDefinition/AgentVersion、Provider、AgentRun/Attempt/Step/Checkpoint 和模型调用审计。
- 已完成正式 AgentRun 执行链路：Provider 调用、Schema 校验、基础结果策略、重试、租约心跳、Recovery、Outbox/Inbox 和 Flowable 恢复。
- 重试已使用可注入的指数退避 + 有界随机抖动，最终 `available_at` 在同一事务中持久化。
- 已完成首节点及审批后 Agent 输入契约：后端按真实路径生成字段，前端动态渲染，命令边界再次校验必填输入。
- 输入映射首版只允许标量、对象字段和整个数组；输出映射支持数组索引和通配投影。

### 工程质量基线

- 已接入 Maven Enforcer、Checkstyle、SpotBugs、JaCoCo、ArchUnit、OpenAPI lint、路由覆盖和兼容性检查。
- 前端已接入格式、Lint、类型检查、单元测试、构建和 Playwright E2E。

## 二、当前阶段与验收

| 优先级 | 工作项 | 当前状态 | 下一步验收 |
| --- | --- | --- | --- |
| P0 | 容器化集成测试 | CI 可执行，本机可能因 Docker 不可用而跳过 | PostgreSQL、Redis、Flowable、Flyway、RLS 和 HTTP 安全集成测试稳定通过 |
| P0 | 生产可观测性 | Trace、基础日志、健康检查和部分指标已有 | 指标、告警、审计查询和失败命令运维入口完整 |
| P0 | 流程操作审计 | 基础审计已有 | 发起、领取、审批、驳回、转办、终止、规则命中可追溯 |
| P1 | 租户纵深隔离 | 平台业务表已启用强制 RLS | 完成 Flowable 内部表评估和跨租户负面测试 |
| P1 | API 契约治理 | 路由已覆盖，部分响应模型仍需细化 | DTO、错误码、分页模型和客户端类型生成完整 |
| P1 | Agent Runtime 生产可靠性 | 首个闭环已有，生产级能力未完成 | Checkpoint 恢复、出站策略、跨实例公平调度、Guardrail 和故障测试完成 |
| P1 | Agent 交互扩展 | 首个输入契约切片已完成 | 版本化表单、复杂对象/数组控件、外部幂等提交和待补录任务完成 |

## 三、下一阶段顺序

1. 先完成 P0 生产基线：集成测试、可观测性和流程审计。
2. 再完成 P1 安全与契约：RLS 深度验证、API 响应模型和核心覆盖率。
3. 再完善 Agent Runtime：结果策略、Checkpoint 恢复、出站安全、公平调度、取消和人工确认。
4. 最后扩展组织、任务中心、表单、通知、委托、SLA、工具治理和证据驱动自治。

## 四、明确暂不做

- Dify 类通用 Agent 内部流程画布。
- 租户上传任意 Java、脚本、插件或任意目标 HTTP 工具。
- 无边界的多 Agent 自主协作、复杂记忆和开放式工具生态。
- 在组织、表单和任务中心基础能力之前扩展高级企业功能。

## 五、完成判定

首个纵向闭环不等于 Agent MVP 完成。只有执行、恢复、幂等、权限、审计、取消、人工交互和 Flowable 集成全部通过容器化集成测试，才可将 Agent MVP 标记为完成。

最近一次验证：`agent_process` 第 3 版已验证“发起流程 → 生成输入契约 → 填写业务字段 → Agent 执行 → 后续节点继续运行”。
