# 项目文档导航

项目文档只维护三个权威类别。状态判断不能从历史课程、旧实施记录或代码注释推断，必须以这三个入口为准。

| 类别 | 唯一入口 | 说明 |
| --- | --- | --- |
| 长期设计 | [long-term-design.md](architecture/long-term-design.md) | 架构边界、领域模型、扩展方向和不可破坏的工程约束 |
| 下一步计划 | [status.md](status.md) | 当前基线、当前阶段、下一步验收和明确暂不做的内容 |
| 待修复问题 | [known-issues.md](quality/known-issues.md) | 仍未关闭的问题、影响、修复方向和验收标准 |

## 支撑资料

- [API 契约](api/README.md)：机器可读接口契约和兼容性规则，不承担产品路线。
- [学习知识库](learning/)：用于理解已实现设计的课程材料，不承担当前状态判断。
- [Agent 协作架构设计](architecture/agent-collaboration-design.md)：长期设计的详细正文。
- [开发方向与防偏离门禁](architecture/development-direction-guardrails.md)：参考项目边界、技术主线、功能准入门禁和代表性验收闭环。
- [RAG 短期实施方案](architecture/rag-short-term-implementation-plan.md)：知识模块、中立检索契约、可靠摄取、Evidence/Citation、评测和图谱扩展边界。
- [MCP 短期实施方案](architecture/mcp-short-term-implementation-plan.md)：连接器、工具目录快照、AgentVersion 绑定、协议调用、安全和可靠性闭环。
- [Agent 资源治理](architecture/agent-resource-governance.md)：长期资源隔离、虚拟线程和公平调度约束。
- [证据驱动的 Agent 自治](architecture/evidence-governed-agent-autonomy.md)：后续自治治理方向。
- [工作流与 Agent 交互契约](architecture/workflow-agent-interaction-contract.md)：当前交互契约和表单扩展边界。

## 文档规则

- 已完成内容只出现在“下一步计划”的当前基线或支撑资料的实现边界中，不重复进入问题清单。
- 计划项必须有优先级、边界和验收标准；没有验收标准的内容只能放入长期设计。
- 待修复问题必须说明当前实现、影响和下一步；修复后从问题清单移除，并在当前基线留下简短记录。
- 详细设计可以保留，但不得自行维护与三个权威入口冲突的状态结论。
- 产品定位和跨仓库路线只在聚合仓库维护，后端不复制一套产品路线。
