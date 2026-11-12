# 长期设计总览

更新时间：2026-11-11

本文是长期设计的索引，不重复实现细节。它回答“系统为什么这样划分、哪些边界不能破坏、未来如何扩展”。

## 一、系统定位

当前业务路径为结构化模块化单体：Flowable 负责业务流程，`agent-engine` 负责 Agent 运行与治理。目标采用 Flowable 业务层、Java 治理层、开源 LangGraph 内部运行模块三层：将内部图编排/Checkpoint/恢复交给框架，平台保留业务 Run、权限、配额、回执和最终完成权。不采用商业 Agent Server；独立模块和本地回归已建立，Java 业务路径尚未迁移。各层通过应用端口、事件和稳定 DTO 协作，不泄露框架类型。

## 二、不可破坏的边界

1. 领域层不依赖 Spring、Flowable、JDBC、Redis 或 HTTP。
2. 应用层通过端口访问外部能力，Provider Adapter 不能被领域对象直接调用。
3. 流程定义、派单规则、表单和 Agent 版本不可变；运行实例必须绑定明确版本。
4. 平台业务读写受可信租户上下文、显式过滤和 PostgreSQL RLS 约束；独立运行服务的状态存储须另行验证租户/线程授权及账号隔离，不能假定自动继承 Java RLS。
5. 远程调用必须有幂等键、状态机、租约、重试和恢复边界；不能用内存状态替代可靠业务记录。
6. Agent 输出必须经过 Schema、结果策略、权限和风险边界校验后才能影响流程。
7. 高风险工具、流程终止、权限变更和外部写操作必须保留人工确认或确定性授权边界。
8. 已发布 Agent 定义、版本和流程引用必须可追溯且不可破坏；Agent 定义只有在未发布、无运行历史且无流程引用时才允许物理删除，否则只能停用并保留历史。
9. LangChain4j 作为模型/MCP/RAG 基础设施适配器；选定的 LangGraph 后端拥有其内部图状态、Checkpoint 与恢复位置，Java 不维护竞争游标。AgentRun 业务状态、权限、工具回执、知识授权和 Flowable 完成仍由平台持有；不再叠加 LangChain4j Agentic 管理同一执行链。
10. 持久化结果/Checkpoint 不是历史授权凭证；复用和每次后续模型请求必须重新校验当前访问边界。知识快照按来源显式固定索引，分数门禁绑定算法/校准版本；原文、结构定位、来源覆盖与精确 Citation 的身份不能由 Chunk ID 或一个无类型浮点数代替。
11. 执行入口建立单调时钟预算，授权/恢复、数据库读取、工具与模型共享截止时间，发出外部调用前重新扣减剩余预算。定位交换契约禁止隐式补值/类型转换；固定知识快照的召回在 TopK 截断前必须具备稳定全序，不能依赖数据库物理顺序。
12. 框架负责内部图推进/Checkpoint/恢复，平台负责业务命令、准入和调用配额，外围 Worker 只领取图实例资格；普通挂起/resume 不算失败 Attempt。跨服务命令靠持久幂等与对账交接，不假设图 Checkpoint 与业务库同事务。虚拟线程、本地 Semaphore 和 TTL 不替代跨实例容量、图存储所有权保护或远端未知结果核对。

## 三、领域演进方向

- 工作流：组织、任务中心、表单、通知、委托、超时和升级。
- Agent Runtime：原生多步骤恢复已形成基线；目标转为 LangGraph 内部执行，按 G0–G3 验证后切换权威并退出旧路径，不继续自研通用图引擎。故障恢复、公平配额、Guardrail 和高风险工具治理仍需验收。
- 人机协作：`SHADOW`、`ADVISORY`、`SUPERVISED` 到受约束自动完成，权限只能按证据升级并可安全降级。
- 设计时生成：先生成受约束草稿，再经过 Schema、BPMN、权限和人工发布门禁，禁止直接写入生产流程。
- 配置生命周期：草稿配置允许在无业务使用时清理；一旦发布或产生运行记录，进入保留、停用和审计优先的生命周期，不提供绕过引用检查的强制删除。
- 技术复用：模型协议、MCP Client、文档解析、Embedding、向量存储和 Rerank 优先评估成熟库；内部图执行/恢复复用 LangGraph。领域契约、租户边界、版本冻结、业务完成和人工治理仍由平台持有，不以框架运行成功替代业务验收。
- RAG 解析选型（2026-11-02）：采用 LangChain4j + MinerU，前者承接 Java AI 通用基础设施，后者作为隔离的专业文档解析服务；平台保留原件、结构/资产归一化、结构感知切分、来源定位和发布治理。不引入第二套 RAGFlow 知识生命周期，不自研 OCR/表格识别模型。解析 recipe/模型变更产生新派生产物与索引，不修改已发布 Profile；图表识别结果须标记派生来源且经过质量门禁。此为目标方案，不表示 PDF 已可用。

## 四、详细设计索引

- [开发方向与防偏离门禁](development-direction-guardrails.md)：参考项目边界、三条技术主线、功能准入条件和代表性验收闭环。
- [RAG 短期实施方案](rag-short-term-implementation-plan.md)：知识限界上下文、中立 Evidence 契约、可靠摄取和多 Retriever 扩展验收。
- [四格式证据摄取总设计](rag-four-format-evidence-design.md)：Excel、Word、PDF、Markdown 各按原生结构取证，统一权限、快照、切分语义和发布门禁。MinerU 只负责 PDF，不是所有材料的必经服务。
- [结构化 PDF 适配设计](rag-structured-document-ingestion-design.md)：LangChain4j + MinerU 选型、原件/结构/资产契约、跨页图表边界、可靠任务及质量验收。
- [结构化 PDF 实施设计](rag-structured-document-implementation-design.md)：开源实践取舍、派生身份/任务恢复/完整来源 Manifest、API/权限、结构切分与分层测试；恢复授权、多来源冻结与有类型评分已实现，MinerU/结构化摄取仍待实施。
- [RAG 评测用例契约](rag-evaluation-case-contract.md)与[专项实施设计](rag-evaluation-and-routing-delivery-plan.md)：结构定位 v1、case v3/dataset v2 交换契约已有代码及回归；完整评测、历史时效/变更复核、发布门禁及查询级路由仍为目标方案。
- [MCP 短期实施方案](mcp-short-term-implementation-plan.md)：MCP 作为受治理工具适配器的连接、目录版本、AgentVersion 绑定、调用和故障边界。
- [Agent 协作架构设计](agent-collaboration-design.md)：Agent 与 Flowable 的长期协作边界、领域模型和治理原则。
- [Agent 资源治理](agent-resource-governance.md)：虚拟线程、租户公平调度、租约续期和分布式配额。
- [LangGraph 内部运行时接入](langgraph-runtime-adoption-design.md)：开源图库/官方 saver、唯一恢复权威、跨服务交接、稳定回执、版本及 G0–G3 验收；独立模块已有回归，业务接入未完成，自研节点调度方案已撤回。
- [证据驱动的 Agent 自治](evidence-governed-agent-autonomy.md)：自治等级、证据、升级和降级。
- [工作流与 Agent 交互契约](workflow-agent-interaction-contract.md)：首节点/审批后 Agent 的交互数据契约和表单扩展边界。
- [后端架构治理](../quality/architecture-governance-and-roadmap.md)：模块依赖、质量门禁、安全和迁移约束。
- [API 契约治理](../api/README.md)：公开接口、响应模型和兼容性规则。

详细设计中的“尚未实现”只表示目标能力；当前状态以[下一步计划](../status.md)和[待修复问题](../quality/known-issues.md)为准。
