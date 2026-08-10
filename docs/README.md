# 项目文档导航

文档分为四类，状态以 [项目状态总览](status.md) 为准：

| 类别 | 入口 | 用途 |
| --- | --- | --- |
| 产品目标 | [聚合仓库产品定位与目标](https://github.com/illuseahashmap/workflow-agent/blob/main/docs/product-positioning-and-goals.zh-CN.md) | 跨仓库产品定位、差异化、能力支柱和阶段目标的唯一权威来源 |
| 当前状态 | [status.md](status.md) | 区分已完成、进行中和下一步目标 |
| 架构规范 | [architecture-governance-and-roadmap.md](quality/architecture-governance-and-roadmap.md) | 不可破坏的架构约束、质量标准和阶段路线 |
| Agent 长期设计 | [agent-collaboration-design.md](architecture/agent-collaboration-design.md) | Agent 与 Flowable 的长期边界和扩展地图 |
| Agent 渐进式自治 | [evidence-governed-agent-autonomy.md](architecture/evidence-governed-agent-autonomy.md) | Agent 基于任务运行证据逐步获得、保持和失去完成权的后续设计方向 |
| Agent 当前实施 | [agent-mvp-implementation-plan.md](architecture/agent-mvp-implementation-plan.md) | Agent 基础设施的实现范围和剩余验收项 |
| API 契约 | [api/README.md](api/README.md)、[api/openapi.yaml](api/openapi.yaml) | HTTP 接口契约和兼容性规则 |
| 学习知识库 | [learning/](learning/) | 按课程理解 Agent 领域模型、持久化和执行链路 |
| 当前问题 | [known-issues.md](quality/known-issues.md) | 只记录尚未关闭的问题和明确延期项 |

## 文档状态规则

- 已完成的工作进入“已完成基线”或历史归档，不再放入当前待办。
- 进行中的工作必须有验收标准和下一步动作。
- 设计文档描述目标，不代表功能已经实现；是否实现以 `status.md` 和代码/测试为准。
- 产品定位与跨仓库阶段目标只在聚合仓库维护；后端文档不得另写一套相互竞争的产品路线。
- 每次跨模块开发完成后，必须同步更新 `status.md`、`known-issues.md` 和相关架构文档。
