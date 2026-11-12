# 下一步计划与当前状态

文档更新时间：2026-11-11。本轮完成 LangGraph 单次 MODEL/确定性 VALIDATE 与原响应持久回执（V66），真实图/HTTP/JDBC 链路通过候选和校验回复丢失恢复；联合回归 52 项、内嵌 Java 60 项通过，另复测原生链路等 66 项。模型仍是确定性替身；生产 Worker/HTTP、路由/压缩/修复图节点及 Flowable 等价尚未完成，业务链路未切换。[采购四格式试点](fixtures/knowledge/pilots/procurement-approval-v1/README.md)保留 Markdown 单块、三格式原生关键词、金额区间与关联块三次实验，并单独验证 Word B09 条件分支与 Excel 单位干扰项。PDF 仅收件。以下为研发进度，不代表生产发布门禁通过。

本文是“下一步计划”唯一入口。长期原则见[长期设计总览](architecture/long-term-design.md)，具体缺陷见[待修复问题](quality/known-issues.md)。

## 一、当前基线

### 平台与工作流

- 已完成模块化单体拆分：`auth-engine`、`workflow-engine`、`rules-engine`、`agent-engine`、`knowledge-engine`、`platform-migrations`、`workflow-boot`。
- 已具备注册登录、HttpOnly Cookie、CSRF、租户/角色/权限、流程版本、派单规则版本和基础审计。
- Cookie 用户携带任意服务令牌头不会豁免 CSRF，只有已认证的 SERVICE principal 可豁免；Redis 流程锁具备多续租线程、失效标记及事务内提交前所有权校验；驳回必须显式指定目标，不猜测第一个 UserTask。
- 已具备流程启动、审批、驳回、转办、终止、实例查询、参与人解析和流程图展示。
- 已建立 PostgreSQL RLS、租户上下文、Redis 锁和可靠兜底命令。

### Agent 首个纵向闭环

- 已完成 AgentDefinition/AgentVersion、Provider、AgentRun/Attempt/Step/Checkpoint 和模型调用审计；平台 Agent Worker 在每个逻辑步骤完成后持久化幂等 Step/Checkpoint，传统执行器保留完成后兼容写入。
- Agent 定义已提供受权限保护的删除接口和前端入口；仅未发布、无运行历史且未被流程引用的草稿定义可物理删除，已发布定义保留以保证流程和审计可追溯。
- 已完成正式 AgentRun 执行链路：Provider 调用、Schema 校验、基础结果策略、重试、租约心跳、Recovery、Outbox/Inbox 和 Flowable 恢复；平台 Agent 的逻辑步骤 Checkpoint 现在包含恢复游标和受限上下文，并在新 Attempt 读取。
- Agent 完成 Outbox 已采用事务提交后本机唤醒、数据库租约领取、有界虚拟线程执行和定时扫描兜底；外部 XXL-Job 仅调用幂等恢复入口。重试时间带随机抖动，历史无消费者的 `AgentRunRequested.v1` 已停止生成并由 V53 保留审计后收口。
- 已增加 `PLATFORM_AGENT` 执行模式的首个切片：计划阶段与执行阶段形成受控循环，仍复用同一 AgentRun 和流程恢复链路；当前仅允许后端显式注册的工具。
- 已增加显式 `AgentTool`/`AgentToolRegistry` 应用端口，结构化 `TOOL_CALL` 只能调用后端注册工具，未注册工具会拒绝；AgentVersion 已冻结工具代码集合，BPMN 节点只能继续收紧，当前尚未开放通用 HTTP、脚本或租户自定义代码工具。
- Agent Definition 通过 `AgentToolCatalogPort` 校验发布工具，不直接依赖 Runtime Registry 实现；V57 在发布事务内冻结工具名称、Schema、只读属性、能力、风险和副作用元数据。运行仍复核当前授权和启用状态；历史版本的 `UPGRADE_BASELINE` 不冒充原始 `PUBLICATION` 快照，缺快照明确失败。升级边界见[工具快照手册](operations/agent-tool-snapshot-upgrade.md)。
- Provider 请求现在携带当前租户已授权工具的名称、描述和输入 Schema；Provider Adapter 通过能力契约声明原生 Tool Calling 支持，Chat Completions 使用结构化工具调用，不支持原生工具的 Responses 兼容端点使用受控文本工具协议。模型不能凭 Provider 凭证获得工具权限，工具仍由 Runtime 注册表和租户授权共同决定。
- 已落地首个只读业务工具 `agent_run_status`，工具定义、租户授权、输入 Schema、稳定幂等键、带租约的数据库原子抢占和执行审计由 PostgreSQL 管理；工具输出不直接修改流程或 AgentRun。
- 已落地真实业务只读工具 `workflow_process_context`：Agent 可按租户读取流程实例元数据、当前人工任务和脱敏业务变量，继续复用 AgentRun/Step/审计链路；工具不能推进或修改流程。知识检索另有独立 `knowledge_search` 端口和授权边界。
- 已建立 `knowledge-engine` 的 Evidence 多态契约、授权范围求交、检索 Trace、`RetrievalProfileVersion` 模型、知识源/文档/索引/摄取任务生命周期模型和 `knowledge_search` 只读工具端口；RAG-2 已新增文档快照、确定性 Chunk 切分、索引构建端口、`knowledge_chunk` 全文检索表、PostgreSQL 文档/索引/Trace 适配器和租约 Worker。当前已增加 AgentVersion 到 RetrievalProfile 的数据库绑定、提交人随 AgentRun 快照传递及主体知识范围授权表/适配器，并新增租户边界内的 Profile 草稿/发布、文档入库与摄取任务、主体范围授权管理 API；前端已提供主体范围授权入口，避免运行时因缺少主体授权而只能失败。V47 已为 Profile、文档、摄取查询和范围授权建立独立权限，前端已提供 ACTIVE 索引选择、Profile 草稿/发布和文本/Markdown 文档提交入口；workflow-boot 已接入按启用租户轮询的摄取调度器，任务会通过租约 Worker 有界领取并执行。V48 已在已有检索应用、Profile 绑定和主体范围授权边界内恢复 `knowledge_search` 只读工具，AgentVersion 可绑定已发布 Profile 后进入真实检索链路；本地浏览器已验证全文检索证据回注、流程路由和后续审批完成。pgvector 向量摄取与 HYBRID 检索本地真实回归已通过；最终答案 Grounding 和发布级离线评测仍未完成。
- 已开放 `PLATFORM_AGENT` 前端配置；流程运行时自动注入受控 `processInstanceId`，模型无需把流程 ID作为业务输入，手动测试仍支持显式传入。
- 已完成 MCP-1/MCP-2 只读配置与运行闭环：Connector/Version、目录发现与审核发布、工具 Schema 快照、AgentVersion 绑定、HTTPS Streamable HTTP 的 initialize/tools/list/tools/call、MCP Adapter 与现有 Registry/租户授权/审计链路连接；前端已提供连接器、目录审核、草稿删除和 Agent 版本工具绑定入口；确定性 HTTPS 协议测试覆盖会话、initialized 通知、SSE 多事件、批量响应、目录发现和工具调用。本地浏览器已验证“模型选择工具 → MCP 调用 → 返回 `count` → AgentRun 成功 → Flowable 完成”。另已补齐 MCP 调用级 Redis 租户令牌桶、并发硬上限、连接器熔断、有界 TTL 会话复用、凭据指纹轮换和 Worker 接管后的 Checkpoint 恢复测试。当前仍未宣称生产级出站安全、确定性 MCP Server 容器故障注入和多实例压力验收完成。
- 前端状态展示已收敛到公共 `StatusBadge`/`TableTagCell` 契约：状态、版本、分类和筛选标签分别使用稳定变体；标签不再在组件内部截断，原始状态码通过悬浮提示保留；流程定义、Agent、工具目录、成员角色、租户、派单规则、参与人和审计页面统一状态列对齐。
- 重试已使用可注入的指数退避 + 有界随机抖动，最终 `available_at` 在同一事务中持久化。
- 已增加类型化失败模型与恢复决策账本：Provider 临时/永久故障、输出/输入契约、工具协议、结果策略、配置、业务拒绝、截止时间和未分类异常均在边界处明确归类，再由恢复策略选择重试、修复、人工介入或终止；运行详情可查询安全诊断信息、Trace ID、Attempt、Step 和恢复决策。
- 已完成首节点及审批后 Agent 输入契约：后端按真实路径生成字段，前端动态渲染，命令边界再次校验必填输入。
- 已提供租户安全的流程实例 AgentRun 关联查询：`GET /agent-runs/process-instances/{processInstanceId}`，外部系统可以跟踪执行状态而不接触内部状态机。
- 已提供受权限保护的失败运行处置命令：修复 Provider、凭证或 Agent 配置后，可通过 `POST /agent-runs/{runId}/retry` 显式开启 30～3600 秒的新执行窗口并创建新的 Attempt；流程仍停留在原 Agent 节点，只有新 Attempt 成功后才恢复 Flowable，不能无限延长原始运行。
- Agent 节点失败策略已形成三种明确语义：`HOLD_FOR_OPERATIONS` 保持在当前节点等待运维，`CONTINUE_EMPTY` 携带失败上下文继续，`MANUAL_REVIEW` 写入稳定的 `agentReviewRequired`、Run/结果状态与错误码变量并恢复 Flowable，由 BPMN 网关进入显式人工复核任务；不再由 Agent Runtime 暗中创建另一套人工任务状态机。
- 输入映射首版只允许标量、对象字段和整个数组；输出映射支持数组索引和通配投影。

### 工程质量基线

- 已接入 Maven Enforcer、Checkstyle、SpotBugs、JaCoCo、ArchUnit、OpenAPI lint、路由覆盖和兼容性检查。
- 已固定 Maven Wrapper（生成器 3.3.4、Maven 3.9.12）及 Redocly CLI 1.34.5/依赖锁；CI 使用 Wrapper、`npm ci` 和本地 CLI。固定版本的实现及本地验证已完成（原 P1-13），全新环境复现与当前提交的远端 CI 状态仍需独立验证。
- 知识 HTTP 请求已拆出 DTO，校验必填、长度、枚举、数值与 Scope；畸形 JSON、缺失/非法参数和越界分页返回 400，业务冲突返回 409，内部异常仍返回 500；Bean Validation、MockMvc 与 OpenAPI 已同步（原 P1-2d）。跨接口契约生成仍属开放工作。
- 前端已接入格式、Lint、类型检查、单元测试、构建和 Playwright E2E。

现有运行边界：旧版已发布 `HYBRID` Profile 若绑定纯关键词索引，运行时维持关键词检索，Trace 记录实际策略；新建 Profile 不允许此组合。该历史兼容行为不是完整向量能力，不得据此声称旧索引已具备向量。

## 二、当前阶段与验收

2026-11-04 四项交叉评审整改已落实代码：工具结果恢复/幂等复用/后续模型请求均重校验权限；结构定位 v1、用例 v3 与数据集 v2；V59 的多来源固定索引与发布不可变；ScorePolicy 与完整证据组筛选。Profile API/OpenAPI、Trace 与回归同步。细节见[实施设计第 13 节](architecture/rag-structured-document-implementation-design.md#13-四项交叉评审整改2026-11-04)和[V59 升级说明](operations/rag-governance-upgrade.md)。这不是 MinerU 接入、真实父块扩展或最终答案 Grounding 完成。

2026-11-02 选定 **LangChain4j + MinerU**（MinerU 仅用于 PDF）。当时仅支持文本；当前 Markdown/XLSX 的原件切片见下表，PDF 解析服务、OCR、跨页表和图题关联仍未接入。

2026-11-03 已补[结构化 PDF 实施设计](architecture/rag-structured-document-implementation-design.md)：参考开源项目官方机制，细化三层内容/派生身份、中立结构和端口、任务阶段/远端代次、完整来源 Manifest、API/权限/预览、迁移与 20 个测试场景条目。该进展属于设计，未锁定/安装 MinerU 或运行解析样本，D0 尚未全部完成。

| 优先级 | 工作项 | 当前状态 | 下一步验收 |
| --- | --- | --- | --- |
| 待重新验收 | 容器化集成测试基线 | 曾完成 Docker 下 271 项、零跳过的历史基线；最近一次后端验证含 21 个环境/显式开关跳过用例，知识数据库套件已在隔离 PostgreSQL 真实执行，不能替代容器门禁 | 执行前重新检查 Docker，再跑当前提交的必需基础设施套件零跳过门禁并归档报告；真实强杀和多实例压力仍按专项验收 |
| P0 | 生产可观测性 | 已有租户隔离的 Runtime 健康摘要、低基数运行/积压/暂停/恢复/死信/租约指标、Prometheus 告警、Runbook 和前端状态化处置入口；已增加本地 Alertmanager 模板、生产 Secret Webhook Overlay、续租调度延迟/耗时/队列/活动注册容量指标与告警；异步 Agent 节点处置审计保留持久命令 Trace ID | Docker Engine 恢复后执行真实告警触发、通知和处置回放，并在多实例环境校验指标准确性与外部 Receiver 可达性 |
| P0 | 流程操作审计 | 租户隔离查询 API 支持事件、实例、Trace、时间范围和分页；人工流程操作及 Agent 节点成功、人工复核、空结果继续、运维保持决策均进入同一审计账本 | 通过容器化环境验证全链路可追溯，继续补齐派单规则命中/兜底决策证据 |
| P1 | 租户纵深隔离 | 平台业务表及迁移时已存在且带租户字段的 Flowable 表已有强制 RLS；最小权限部署模板未完成 | 先补独立迁移/运行账号模板，在非超级用户下完成真实 Flowable 表评估、系统 Worker 授权和跨租户负面测试 |
| P1 | API 契约治理 | 路由覆盖、成功响应模型和 Agent 运行接口错误响应已继续收敛 | 全部认证接口错误响应、DTO、分页模型和客户端类型生成完整 |
| P1 | Agent Runtime 生产可靠性 | Heartbeat 已改为实例级固定共享调度池并限制活动注册总量，具备调度延迟、续租耗时、队列、活动数和容量指标；[Checkpoint v5](architecture/agent-collaboration-design.md) 增量合并工作摘要，按当前权限回读不可变工具记录，核验声明的决策字段。同租户两单、连续两次压缩、成功工具回执缺失、终态事务回滚、预算调小、JSON 修复与 Flowable 分支已纳入 PostgreSQL 回归 | 按 LangGraph G0–G3 验证接入、跨实例故障与容量，迁移双采购恢复基线并排空旧运行；外置大对象、配额/Guardrail 等治理继续保留，不为新图另建 Java 内部 Recovery |
| P1 | Agent 交互扩展 | 输入契约和流程实例运行查询已完成首个切片 | 版本化表单、复杂对象/数组控件、外部幂等提交和待补录任务完成 |
| P1 | 结构化意图与能力路由 | `PLATFORM_AGENT` 已将原自然语言 `PLAN` 升级为版本化 `RouteDecision`，支持 `DIRECT/RAG/MCP/RAG_MCP/ABSTAIN`；工具目录持久化 `DIRECT/RAG/MCP` 能力、风险和副作用元数据，路由不依赖工具命名；LangChain4j 优先发送冻结 JSON Schema，仅在 Provider 明确不支持时于同一总预算内降级 JSON mode；工具交集和路由冻结到 Checkpoint | 增加离线路由评测集和主体权限/风险策略的显式路由投影；用真实 DeepSeek 分别回归 DIRECT、RAG、MCP、RAG_MCP、ABSTAIN，确认降级、失败策略与流程分支 |
| P1 | LangChain4j 基础设施复用 | Chat Completions、Responses API 与 MCP 协议处理已默认切换到 LangChain4j，原适配器保留为显式回滚开关；平台端口、治理和可靠执行语义未变 | 继续验证文档解析、Embedding、向量存储和 Rerank 适配；扩充真实 Provider 与 MCP Server 兼容矩阵 |
| P1 | 四格式证据摄取（P1-2e） | V60 保存四种原件、解析清单与定位；Markdown/XLSX/DOCX B04 经过发布服务及 Registry，四块缺一不放行。界面支持上传与按现权回读片段 | 扩展完整证据组召回、复杂 Office 结构、PDF MinerU D0；补真实流程浏览器联测、多 recipe 重建和分格式质量门禁 |
| P1 | 受治理 RAG 最小闭环 | 关键词闭环已验证；可选 pgvector/方舟 Embedding、RRF 混合召回、索引版本固定和基础离线指标已接入。`employee-handbook.md` 第 1 版生成活跃索引第 2 版及 3 条 1024 维向量；新 Profile 4、AgentVersion 14 和采购流程第 3 版已发布。真实 AgentRun 50 的 HYBRID Trace/Citation 与 Flowable 直属负责人审批任务均通过本地验收；Ark 暂时/永久失败分类及有界重试已加固。十领域 40 篇文档草稿、评测用例契约和专项实施设计已形成，但未审核入库或建立锁定评测集 | 浏览器 UI 验收、最终答案 Citation 门禁、知识快照/历史时间查询、评测集发布门禁、摄取/Agent 强杀恢复、多模型版本治理和 Docker 容器门禁仍未完成 |
| P1 | 权威组织关系解析 | 方向已与 RAG 拆分，尚未建立 `organization-engine` 和组织关系数据模型 | 完成组织实体/关系有效期、有限跳查询、参与人策略、空/多人/停用兜底、路径审计和跨租户负面测试 |
| P1 | 受治理 MCP 最小闭环 | MCP-1/MCP-2 本地浏览器链路和确定性 HTTPS 协议测试已完成；租户限流/并发配额、熔断、会话复用、凭据指纹轮换及 Checkpoint 接管已有测试；SDK/原生 MCP 共用连接时 DNS 地址校验与有界传输，基础重绑定拒绝已有回归 | 补真实 Secret Provider、确定性 MCP Server 容器故障注入、生产代理/网关边界、多实例租户治理及 Worker 强杀验收；完成前不得称为生产可用只读 MCP 闭环 |

## 三、下一阶段顺序

2026-10-30 的四项逻辑缺陷已整改：知识源索引按完整文档快照构建并验证、所有模型/Embedding/MCP 出站传输统一连接地址校验、Profile 版本通过 V58 原子分配、非法/缺失请求参数返回 400。源快照、pgvector 数据、8 请求版本竞争已完成隔离 PostgreSQL 回归；已有已发布 Profile 继续固定原索引，新知识快照需显式发布新 Profile 版本。

问题清单现按“待实现 / 已修复待验收 / 部署风险”分类。原 P1-10 合并到 P1-2a；P1-2d/P1-13 的代码与本地回归已完成，不再列为同名开放缺陷。跨实例配额、非超级用户 RLS、生产告警、Grounding/评测门禁和容器故障验收仍需继续，不能据此将整个平台标记为生产闭环。

2026-11-02 主线转向 RAG 结构化摄取，2026-11-04 已落实前置治理代码，MinerU 仍未接入。2026-11-11 根据平台并发与避免重复造轮子的要求，当前优先项调整为下述运行时架构接入，RAG 路线及现有基线保留。开发顺序不是风险排序：P0、非超级用户 RLS、Runtime 和容器门禁继续开放。

### 当前优先项：LangGraph 内部运行时与平台治理

目标已明确为 [LangGraph 接入设计](architecture/langgraph-runtime-adoption-design.md)，不是继续开发自研 ExecutionTask/Continuation。现有 Java 原生执行器仍在运行，P-17 原生 7/7 不能记为框架接入成绩。

1. **G0：补完部署和恢复契约。** 图内恢复、检查点所有权、JDBC 授权/引用/整图命令、MODEL/TOOL/VALIDATE 已有测试。下一步显式迁移路由、压缩、回读和有限修复节点，再接生产 Worker/HTTP、命令自动对账与 artifact 发布路由；不能用原生整 Run 执行器包一层代替，也不因基本链路跑通提前切换。
2. **G1：复用既有治理跑 P-17。** Java 保留模型/工具/知识 Gateway、回执、授权和 Flowable 最终验收；框架接管内部游标。用同七场景和双采购压缩/恢复基线验收。本机脚本模型先行，方舟真实模型继续暂缓，不绕过出站保护。
3. **G2：补跨实例配额、公平性及故障容量报告。** 验证慢供应商不拖垮独立资源、挂起不占执行槽、重试预算不叠加；成功工具/候选恢复不依赖模型重答，旧 Worker 不能污染图状态。
4. **G3：新版本切换、旧运行排空并删除重复代码。** AgentVersion 固定 backend/artifact/state schema；禁止把运行中 v5 改为图状态、双执行或修改已发布版本。只有验收通过才移除原生内部循环/Recovery。

本轮完成 G0 的可执行契约切片，G0–G3 整体均未完成；不新增通用图画布、写工具或第二套节点调度引擎。

2026-11-11 用户已确认开源路线，商业 Agent Server 授权不再作为阻碍。新增 [agent-runtime-langgraph](../agent-runtime-langgraph/README.md)：框架图控制流、同步意图检查点、引用信封、持久等待、同会话 advisory lock + 官方 saver；未接生产 Gateway、未改现有 AgentVersion/Worker。通过脚本 `scripts/test-langgraph-runtime.ps1 -PostgresBin 'C:/Program Files/PostgreSQL/18/bin'` 跑 **27 项测试（21 图逻辑 + 6 PostgreSQL），0 skipped**，包括同租户两 Run、两次等待、拒绝不重答、进程强杀回执/候选复用和旧数据库会话不能写入。测试使用独立临时集群，不依赖 Docker/方舟，也不冒充 P-17 Java/Flowable 联调；容量/网络分区仍待验收。依据与边界见[接入设计](architecture/langgraph-runtime-adoption-design.md#10-已实现切片与回归证据2026-11-11)。

同日继续接入：新增 [治理 Gateway 契约](architecture/langgraph-governed-gateway-contract.md)、Java 工具桥接和 Python 有界 HTTP 客户端。加 `-IncludeJava` 联合脚本实测 **52 passed / 0 skipped**（原有 27 + HTTP 24 + Java 启动 1）；Java 启动测试实际跑 19 Gateway、12 Registry、1 PostgreSQL/跨语言联调、4 架构约束，均通过，不重复相加计数。覆盖丢 ACK 后重放、同租户三 Run 独立能力/参数、6 请求原子抢占、撤权、慢响应头/正文和暂态失败分类。当前接口仅测试服务器暴露，权威授权/引用存储仍使用夹具，未接 Flowable，MODEL/VALIDATE 明确拒绝；没有启用新业务版本、改业务库或调用付费模型。另复测原生 Executor/Worker 与 Registry/Gateway 共 60 项，通过；不把重叠测试重复计数。

同日后续（替代上一段“权威/引用存储仍缺实现”的状态）：新增 V65 和 JDBC 执行会话/不可变引用/整图命令仓储，非自动注册生产 Bean。原生只领取 NATIVE；reserve 不接管有 Attempt 的运行，事务内同时冻结绑定和登记命令。能力仅存哈希，重领换代；等待释放执行权，旧代次不能续租/提交；观察结果不直接完成业务 Run。新增 **13 项真实 PostgreSQL 集成**（含实际 V65、非 owner RLS、八并发单赢家、故障回滚），联合脚本仍为 52 项，内嵌 Java 增至 **49 项**，零失败/跳过。交叉审查修复与尚未启用部分见 [Gateway 契约](architecture/langgraph-governed-gateway-contract.md#持久交接与交叉审查)。下一步是 MODEL/VALIDATE、可信模型结果提交及生产准入/命令对账，不是扩展 Java 图内调度。

同日 V66 进展（当前状态）：`GraphCognitionGateway` 通过已有 Provider 端口执行一次模型调用；原响应先落库，工具参数与模型回执原子发布。VALIDATE 共用原生拒绝/Schema/证据门禁，不调用模型；拒绝、撤权、串单均不能靠重答绕过。新增真实 Python 图 → HTTP → JDBC 权威/回执 → Registry 联调，丢 model:2 与 validate:1 回复后重新打开 PostgreSQL 图恢复，模型始终 2 次、工具 1 次。累计 **52 项外层 / 60 项内嵌 Java**，另复测原生 Executor/Worker/结果策略/Registry/Gateway **66 项**，均通过且不相加计总。交叉审查补了重复 JSON 字段拒绝、响应落库可回读、模型迟到结果拒绝等。没有启用业务版本、访问付费模型或推进 Flowable；剩余边界见 [MODEL/VALIDATE 契约](architecture/langgraph-governed-gateway-contract.md#model-与-validate-的持久边界v66)。

### 保留的 RAG 与发布路线

1. RAG 专项继续按[四格式原生证据摄取](architecture/rag-four-format-evidence-design.md)。原生金额表已改为先匹配业务范围、后匹配金额，H02/H08/H10 纳入[三格式固定 K 回归](fixtures/knowledge/pilots/maintenance-holdout-v1/scoped-v2/README.md)。已用另一份[样品流转制度与规模实验](fixtures/knowledge/pilots/specimen-transfer-v1/README.md)复核：固定 Top5 取全 6/8；扩大原生块数后并发延迟显著上升。范围目录扫描已完成[V63 性能与排序复测](fixtures/knowledge/pilots/specimen-transfer-v1/scope-postings-v63/README.md)。[V64 比较双方证据复测](fixtures/knowledge/pilots/specimen-transfer-v1/comparison-v64/README.md)已收口 S05/H10。[S06 受控术语复测](fixtures/knowledge/pilots/specimen-transfer-v1/controlled-terms-v1/README.md)已补齐两个规模的固定 Top5，缺触发原文时知识工具拒绝。真实模型有证据作答与缺证拒答仍待在合规出站环境验收，当前按用户要求暂缓；不能用工具层结果替代。PDF 的 MinerU D0 可并行。
   - P-17 [联合验收](fixtures/knowledge/pilots/p17-joint-v1/README.md)已将同 Run 的检索回读、工具审计与 Flowable 去向接入评分器。2026-11-10 本机七个 DEV 变体三项均为 7/7：真实 PostgreSQL/Worker/Flowable、脚本模型、进程内只读订单服务。实测跨 Run 互换任一证据段均不可判定，嵌入其他 Run 的工具回执判失败。MCP 快照重复绑定、解绑、重绑再发布已回归。本轮未调用方舟；后续真实模型重复试验与独立复核另行验收。

2. 按[评测与路由专项](architecture/rag-evaluation-and-routing-delivery-plan.md)在固定身份与快照下分别验收四种格式。各格式 E1 依赖其自身结构质量与可追溯索引；收口 Grounding/Citation、历史时效/金标复核和发布门禁后，再依据基线决定是否启动加权融合、重排或查询路由。
3. 在生产发布前完成 P0 运维闭环：重新检查 Docker/通知接线，补齐派单规则命中/兜底审计，真实触发 Prometheus/Alertmanager 告警并回放通知、前端处置和流程审计；完成独立迁移/运行账号模板与非超级用户 RLS 验证。
4. 完成 Agent Runtime 和知识摄取真实故障验收：Worker 强杀、租约过期、外部调用边界暂停和跨实例压力；外置 RAG Evidence/超大对象并建立恢复等价评测，原生与目标图分别按各自租约权威验收，不重复建设图内恢复。遵循 V55/V56 停旧 Worker、V57 工具快照和 V58 来源快照/Profile 计数器手册，不重写历史版本。同阶段完成结构化意图路由五种模式、一次修复、越权、降级、失败策略和 ABSTAIN 的真实 Provider 及评测验收，补主体权限/风险显式投影。
5. MCP 进入生产强化而非重新开发协议：LangChain4j 已接管 initialize、工具目录、JSON-RPC 关联和 tools/call；HTTPS 连接地址防护已有实现，下一步完成生产代理/网关边界、真实 Secret Provider、确定性 MCP Server 容器故障注入和多实例租户治理压力测试。人工确认与未知结果处置完成后再实施 MCP-4 写工具。
6. 完善 Agent Runtime 业务门禁：版本化 Guardrail、成本预算、租户业务规则、公平调度和高风险写工具人工确认。
7. 组织关系解析作为独立并行路线，使用 `organization-engine` 和 `OrganizationGraphQueryPort`，不得并入 RAG 检索编排，也不得由模型决定审批参与人。
8. 最后扩展任务中心、表单、通知、委托、SLA、完整工具治理和证据驱动自治。

### LangChain4j 采用边界

LangChain4j 用于减少通用 AI 基础设施重复建设，不替换业务领域和治理。内部图运行时目标由 LangGraph 接管，二者不竞争编排/恢复权威；独立模块已开始实施，但业务接入门禁通过前原生执行器仍是当前路径。

优先复用：

- Chat Model 与不同 Provider 的协议适配；
- Function Calling、工具 Schema 和结构化输出基础能力；
- MCP Client 的 Streamable HTTP、SSE 和协议兼容处理；
- 文档解析、Embedding Model、Embedding Store 和 Rerank 适配；
- 多 Retriever 查询、聚合等可替换 RAG 基础组件。

平台继续拥有：

- `AgentDefinition`、不可变 `AgentVersion` 及租户权限；
- AgentRun/业务 Attempt、Step 审计投影和框架执行/Checkpoint 关联；内部恢复位置由选定 backend 持有；
- 业务命令、Outbox/Inbox、调用幂等和最终迟到结果拒绝；原生 Lease/Heartbeat/Recovery 仅保护原生后端，目标图内租约与恢复由 runtime 负责；
- Tool Registry、MCP 目录审核、版本冻结、凭据、配额和执行审计；
- RetrievalProfile、知识范围授权、Evidence/Citation/Trace 和 Grounding 门禁；
- Flowable 恢复、人工确认、人工接管和证据驱动自治。

实施规则：

1. LangChain4j 只能通过现有或新增的应用层端口接入，不得让领域和应用层依赖其类型。
2. 优先建立 `LangChain4jMcpClientAdapter`、模型适配器及 Embedding/Rerank 适配器，不一次性重写 Agent Runtime。
3. LangChain4j `AI Services`、`ChatMemory` 和实验性 Agentic 能力不得成为流程状态、运行账本或恢复语义的权威来源。
4. 替换保持租户、版本、权限、审计和 Flowable 结果契约；内部 Checkpoint 按 backend 单一权威管理，不要求跨框架共用原生 v5。版本绑定与旧运行排空遵循 G3。
5. 适配验证至少比较协议兼容、故障分类、恢复行为、可观测性、依赖稳定性和删除的自研代码量；不能只因框架存在就迁移。
6. 新增模型协议、MCP 传输、Embedding、向量库或 Rerank 能力时，应先评估 LangChain4j；无明确缺口不得继续手写同类通用组件。
7. 结构化 PDF 采用 MinerU 专业解析适配，不要求 LangChain4j 自身完成 OCR/表格识别。通用切分、RRF 与重排优先验证 LangChain4j 复用；结构/资产归一化、富结构切分、版本/权限与发布治理仍由平台持有。当前通用组件不因方案确认就视为已替换。

当前默认实现与回滚边界：

- `WORKFLOW_AGENT_PROVIDER_OPENAI_ADAPTER=langchain4j`：默认使用 LangChain4j Chat Model；`/chat/completions` 使用 `OpenAiChatModel`，`/responses` 使用 `OpenAiResponsesChatModel`；设为 `legacy` 可回滚。两条协议均复用平台有界 HTTP、超时和类型化失败边界。
- `WORKFLOW_AGENT_MCP_CLIENT=langchain4j`：默认使用 LangChain4j MCP Client；设为 `legacy` 可回滚。
- MCP 的 JSON-RPC、初始化、工具发现和调用语义由 LangChain4j 维护；平台只保留 HTTPS、凭据注入、硬响应上限、超时、会话生命周期和故障分类等基础设施约束。模型规划与后续工具调用共享同一个绝对截止时间，每一步只获得剩余预算，避免按最大步骤数机械切分导致正常调用提前超时。

## 四、明确暂不做

- Dify 类通用 Agent 内部流程画布。
- 租户上传任意 Java、脚本、插件或任意目标 HTTP 工具。
- 租户配置任意本地 MCP `stdio` 命令，以及未经审核即动态使用 MCP 工具。
- 无边界的多 Agent 自主协作、复杂记忆和开放式工具生态。
- 使用 LangChain4j 高层 Agentic/Memory 替代平台持久化运行账本、Flowable 编排或人工治理边界。
- 在组织、表单和任务中心基础能力之前扩展高级企业功能。

## 五、完成判定

### 2026-11-04 二次三项整改与收尾交叉审查

- 执行 `mvnw.cmd -q -pl workflow-boot,agent-engine,knowledge-engine -am verify`：364 项中 343 通过、21 项环境/显式开关跳过，0 失败、0 错误；Checkstyle、SpotBugs、JaCoCo 与架构门禁通过。相对此前 346 项新增 18 项回归，未降低覆盖门槛；共享预算组件新增测试依赖，不引入业务运行时框架。
- 单调预算从入口覆盖凭据读取、恢复授权及模型；使用可控时钟验证初次请求、恢复、三类修复、授权扣减、迟到响应、作用域退出和嵌套预算。收尾交叉审查发现工具抢占后也有同类旧 timeout 窗口，已一并收紧并覆盖“扣减后执行/耗尽不执行”两种情况。
- 隔离 PostgreSQL 18.1/pgvector 套件现为 10 项、全部通过：新增真实 `pg_sleep` 取消/原 Statement 超时恢复/连接复用，以及多来源同分、不同扫描偏好、K=1/2/4 的关键词/向量/HYBRID 稳定 TopK。连接获取期间过期时不发 RLS SQL并关闭连接，也有单测。未修改业务库，未改历史迁移。
- Codec 和 Schema 负例覆盖缺失/null 坐标、非整数版本/页号、数字字符串、非文本标识、重复键和未知字段，保留正常文本/跨页单元格往返。`npm run test:rag-contract` 4 项通过；40 篇/10 领域语料、链接/SVG 哈希检查通过。
- 收尾按实现/测试/契约/运维文档交叉核对：未发现本次三项范围内遗留的 P0/P1；不将静态复查视为全部生产风险消失。方案与问题记录已同步，详细机制及连接池/取消传输限制见[实施设计第 14 节](architecture/rag-structured-document-implementation-design.md#14-二次交叉评审整改2026-11-04)。
- OpenAPI lint 通过，仍有 59 条既有警告；本轮没有改动公开 API 路由/Schema。10 份改动 Markdown 的本地链接及问题分类、差异空白均检查。没有启动业务服务、调用付费模型、执行浏览器/MinerU/真实强杀或远端 CI；21 项跳过仍不计为通过。修复、回归与文档随本轮提交，远端 CI 结果须另行确认。

### 2026-11-04 首批四项治理整改验证（以下为二次整改前记录）

- 基线为 `main 1a470a1` 后的本地未提交代码。执行 `mvnw.cmd -q -pl workflow-boot,agent-engine,knowledge-engine -am verify`：346 项中 325 通过、21 项环境/显式开关跳过，0 失败、0 错误；Checkstyle、SpotBugs、JaCoCo 和架构门禁通过。
- 数据库套件 8 项在专用临时 PostgreSQL 18.1 + pgvector 0.8.7（本机独立端口 55439）真实执行；包含 V58 已发布 Profile→V59 升级、旧字段保持不变、多来源召回/授权子集、知识源/文档停用、冻结旧索引、拒绝不完整来源与无向量配方、发布后不可变、非超级用户跨租户 RLS 负例。验收后已停止该临时实例，未修改本机业务库；该负面测试不代替全部运行账号/Flowable RLS 验收。
- 恢复/幂等撤权、历史工具上下文不得发往模型、工具已不可用且缺账本时拒绝恢复、Trace 伪造/可见性、定位 Codec/来源覆盖与精确引用、分数尺度与完整组选择均有 Java 回归。模型端使用确定性测试替身，无付费模型调用。
- `npm run test:rag-contract` 4 项通过，已接 CI；覆盖文本/跨页单元格/图像定位和拒绝格式错误、多来源数据集清单。该脚本不是完整数据集锁定/权限/标注语义校验器。语料检查 40 篇草稿、10 个领域、链接/SVG 哈希通过。
- Redocly lint、OpenAPI 路由覆盖和成功响应模型检查通过，保留 59 条既有 lint 警告。本机 Node 24.14 的 CLI 退出异常使用已安装 Node 24.22.1 复核，依赖仍按锁文件；本次未变更运行时版本。10 份改动 Markdown 的 93 个本地文件链接及 18 个开放问题分类已检查。
- 当前 Docker 不可用，21 项跳过不计为通过；未执行真实 MinerU、浏览器、Worker 强杀或跨实例压力，也未查询远端 CI。部署先按 [V59 手册](operations/rag-governance-upgrade.md)停旧 Worker/写入口，不修改历史迁移，不盲目 repair。PDF 摄取、最终答案 Grounding、评测发布与生产门禁仍未完成。

### 历史验证记录

文档选型同步检查（2026-11-02）：LangChain4j + MinerU 方案与长期/短期/问题入口已同步；8 份本次文档的 68 个本地链接/锚点及 18 个开放问题的唯一分类校验通过；语料脚本检查 40 篇草稿、10 个领域、链接和 SVG 哈希通过。本次不包含业务代码、依赖安装或运行验收，以下业务测试日期保持不变。

实施设计检查（2026-11-03）：7 份本次文档的 70 个本地链接/锚点与 18 个开放问题唯一分类校验通过；40 篇草稿、10 个领域、链接/SVG 哈希校验及差异空白检查通过。新增 20 个场景条目的设计矩阵，不表示 fixture/金标已生成或测试已运行；没有启动 MinerU、下载模型、修改代码或执行后端/浏览器业务回归。

首个纵向闭环不等于 Agent MVP 完成。只有执行、恢复、幂等、权限、审计、取消、人工交互和 Flowable 集成全部通过容器化集成测试，才可将 Agent MVP 标记为完成。

LLM/MCP/RAG 联合浏览器纵向验证记录（2026-10-12；RAG 后续向量回归见下文）：

| 场景 | 流程实例 | 结果 |
| --- | --- | --- |
| LLM | `2237f384-c5e9-11f1-9c3f-00e04c6801c9` | AgentRun `46` 模型调用成功并输出 `APPROVE`，后续人工任务完成，Flowable 流程结束 |
| MCP | `a0dc096f-c5ea-11f1-ad03-00e04c6801c9` | AgentRun `48` 选择只读 MCP 工具并完成远程调用，结果回注模型，AgentRun 与 Flowable 流程均完成 |
| RAG | `da84796a-c5ea-11f1-ad03-00e04c6801c9` | AgentRun `49` 在一次网络握手临时故障后按可重试语义恢复，`retrievalStatus=FULL`，命中 `employee-handbook.md` 第 1 版并完成直属负责人审批 |

以上证明本地配置与运行纵向链路可用，不替代专项容器故障注入、Worker 强杀、跨实例、出站安全和质量评测门禁。

RAG 向量纵向验证记录（2026-10-17）：方舟 `doubao-embedding-vision-251215` 成功生成 `employee-handbook.md` 第 1 版的活跃索引第 2 版和 3 条 1024 维向量；Profile 4、AgentVersion 14、采购流程第 3 版及 AgentRun 50 的 HYBRID Trace/Citation 和直属负责人审批任务已本地验证。该记录不等于十领域语料审核、最终答案 Grounding 或发布级评测完成。

历史后端质量门禁（2026-10-30，整改代码已纳入 `7a92bb5`）：通过 Maven Wrapper 执行全量 `verify`，331 项中 310 通过、21 个环境/显式开关用例跳过，0 失败、0 错误；Checkstyle、SpotBugs、JaCoCo 和架构/迁移门禁通过。知识/pgvector 数据库套件 6 项使用专用临时 PostgreSQL 18.1 真实执行通过，不在跳过数中；覆盖新建及 V54→V58 升级、歧义升级拒绝、幂等/不可变性、旧 owner/Attempt 写入拒绝、原子激活回滚、完整来源与向量集合、候选过期拒绝和 8 请求 Profile 版本竞争/事务回滚。该隔离实例结果不代替非超级用户 RLS、容器或多实例压力验收。

同批 Redocly lint 通过并保留 59 条已有警告，OpenAPI 路由覆盖和成功响应模型检查通过，`npm ci --ignore-scripts` 成功。知识数据库套件已加入 CI 必跑门禁，跳过不能算门禁通过；本次文档核对未查询 `7a92bb5` 的远端 CI 结果。

历史对照：2026-10-14 后端 `verify` 为 290 项、272 通过、18 跳过；2026-10-06 完整 Docker 基线为 271 项、零跳过、5 个必需基础设施套件通过。不能用历史 Docker 结果为当前提交背书。前端保留的最近基线为格式、Lint、类型检查、43 个单元测试、构建、包体预算和 14 个 Playwright 场景通过，本次没有重新验证前端。

### 2026-10-30 已完成整改

- 文档身份与正文：服务端规范化 BOM/换行并计算 SHA-256，重复提交保留原正文和任务状态，同版本不同内容/伪造指纹返回冲突；V55 禁止历史身份、指纹及正文改写。历史数据完整性审查见 P1-2b，不自动重写历史 Citation。
- 摄取与快照：V56 将任务确定绑定文档版本，owner/Attempt fencing 约束续租与终态；索引激活/任务完成同事务提交，配套有界虚拟线程与心跳。来源重建保留完整最新有效文档集合，构建/激活拒绝过期候选，Chunk 标识包含文档版本 ID，旧 Profile/索引不原地改变。剩余强杀/多实例验收见 P1-2c。
- 出站连接：模型、原生 Provider、标准/Ark Embedding、SDK/原生 MCP 共用 GovernedHttpTransport，连接 DNS 校验覆盖混合解析、IPv4/IPv6 私网与保留地址，保留 TLS 主机名验证、禁止重定向/隐式重试、有界读取和可取消总超时；基础重绑定拒绝及 HTTPS 协议回归通过。私有服务审批与生产网关仍见 P1-5。
- Profile 版本：V58 按租户/Profile 原子分配版本，初始化存量最大版本，事务回滚不保留分配；8 个并发请求分配 1–8，无唯一键 500，存量升级与回滚已有真实 PostgreSQL 回归。
- 工具发布：V57 冻结不可变元数据快照并保留来源标识，撤权仍生效；快照不可变和跨租户引用约束已有数据库回归，并发发布/生产验收见 P1-9。
- 请求契约及工具链：原 P1-2d/P1-13 的实现与本地回归已完成。请求绑定和类型转换返回稳定 400，业务冲突 409；Wrapper/Redocly 版本和锁文件固定。全局契约生成、新环境复现与容器门禁不随之自动关闭。

部署手册：[摄取与 V55/V56](operations/knowledge-ingestion-upgrade.md)、[工具快照 V57](operations/agent-tool-snapshot-upgrade.md)、[来源快照与 V58](operations/knowledge-source-snapshot-upgrade.md)。生产告警、最小权限 RLS、强杀、多实例、最终答案 Grounding 和评测门禁仍为开放工作。

### Agent Runtime 近期加固

- 已提供租户范围 Runtime 健康摘要，统一展示状态分布、最老排队时间、过期租约和需人工介入数量；定时健康指标只使用状态、结果等低基数标签，不把 tenantCode、runId 或 traceId 写入指标标签。
- 已实现协作式暂停/恢复：排队运行立即暂停，运行中请求在模型/工具调用后的持久化安全边界生效；恢复保留最后完整 Checkpoint 并重新排队，暂停、恢复、取消和重试均写入状态历史与操作账本。
- 前端运行记录页根据实际状态只展示合法动作，并在详情中统一呈现失败分类、错误码、Attempt、Step、恢复决策和安全诊断；本地浏览器已验证 `QUEUED -> PAUSED -> QUEUED -> Worker 重新领取` 以及输入契约失败的可定位信息。
- 已补充 Agent Runtime Prometheus 告警与逐项 Runbook；Alertmanager 接线、容器故障注入和真实多实例演练仍属于发布验收，不以本地浏览器验证替代。
- Provider 适配器通过能力契约声明协议和原生 Tool Calling 能力，运行时不再直接根据凭证推断能力；Responses 兼容端点继续使用受控文本工具协议。
- Provider HTTP 错误只提取有长度限制且脱敏的诊断摘要，完整原始响应不落库；恢复决策账本会向授权运维界面提供可操作上下文。
- Agent Worker 领取查询支持租户级并发上限，避免单一租户占满所有执行槽位；上限通过 `WORKFLOW_AGENT_WORKER_MAX_RUNNING_PER_TENANT` 配置。
- 人工重试除状态历史外，写入独立的 `agent_run_operation` 操作账本；V31 同时为 Agent 操作和已存在的 Flowable 租户表补充纵深隔离策略。
- 结果策略支持显式 `resultStatus` 业务结果封套，只有 `SUCCESS` 才允许恢复流程；空结果、部分结果和业务拒绝均保持终止语义。
