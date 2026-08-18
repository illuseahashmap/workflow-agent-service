# 长期设计总览

更新时间：2026-08-18

本文是长期设计的索引，不重复实现细节。它回答“系统为什么这样划分、哪些边界不能破坏、未来如何扩展”。

## 一、系统定位

项目是结构化模块化单体：Flowable 负责业务流程、人工任务、长期状态和事务一致性；`agent-engine` 负责模型调用、Agent 运行账本、结构化输出、重试和成本审计。两者通过应用层端口、事件和稳定 DTO 协作，不互相依赖基础设施实现。

## 二、不可破坏的边界

1. 领域层不依赖 Spring、Flowable、JDBC、Redis 或 HTTP。
2. 应用层通过端口访问外部能力，Provider Adapter 不能被领域对象直接调用。
3. 流程定义、派单规则、表单和 Agent 版本不可变；运行实例必须绑定明确版本。
4. 所有业务读写同时受到可信租户上下文、显式过滤和 PostgreSQL RLS 约束。
5. 远程调用必须有幂等键、状态机、租约、重试和恢复边界；不能用内存状态替代可靠业务记录。
6. Agent 输出必须经过 Schema、结果策略、权限和风险边界校验后才能影响流程。
7. 高风险工具、流程终止、权限变更和外部写操作必须保留人工确认或确定性授权边界。

## 三、领域演进方向

- 工作流：组织、任务中心、表单、通知、委托、超时和升级。
- Agent Runtime：Checkpoint 多步骤恢复、取消/暂停/恢复、Provider 出站安全、租户公平配额和工具治理。
- 人机协作：`SHADOW`、`ADVISORY`、`SUPERVISED` 到受约束自动完成，权限只能按证据升级并可安全降级。
- 设计时生成：先生成受约束草稿，再经过 Schema、BPMN、权限和人工发布门禁，禁止直接写入生产流程。

## 四、详细设计索引

- [Agent 协作架构设计](agent-collaboration-design.md)：Agent 与 Flowable 的长期协作边界、领域模型和治理原则。
- [Agent 资源治理](agent-resource-governance.md)：虚拟线程、租户公平调度、租约续期和分布式配额。
- [证据驱动的 Agent 自治](evidence-governed-agent-autonomy.md)：自治等级、证据、升级和降级。
- [工作流与 Agent 交互契约](workflow-agent-interaction-contract.md)：首节点/审批后 Agent 的交互数据契约和表单扩展边界。
- [后端架构治理](../quality/architecture-governance-and-roadmap.md)：模块依赖、质量门禁、安全和迁移约束。
- [API 契约治理](../api/README.md)：公开接口、响应模型和兼容性规则。

详细设计中的“尚未实现”只表示目标能力；当前状态以[下一步计划](../status.md)和[待修复问题](../quality/known-issues.md)为准。
