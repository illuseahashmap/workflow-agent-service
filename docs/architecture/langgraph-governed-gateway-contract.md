# LangGraph → Java 受治理调用契约

更新时间：2026-11-12。状态：**MODEL/TOOL/VALIDATE 已联调；V67/V68 治理、认证派发宿主/CLI、账户配置与审计已接通，图入口默认关闭。模型为确定性替身，未完成业务后端切换和生产容量验收。**

本文补充 [LangGraph 接入方案](langgraph-runtime-adoption-design.md)，不另建执行状态机。

## 当前实现范围

- Python `HttpGovernedGateway` 实现既有 `GovernedGateway`，固定服务地址、严格 JSON、身份关联、有界响应和可分类故障；不隐式重试。
- Java `GraphToolGateway` 通过现有 `AgentToolRegistry` 完成工具授权、冻结 Schema、只读检查和原子幂等账本；不复制工具执行器。
- Java `GraphGatewayProtocol` 负责严格解码和安全错误输出；`workflow-boot` 的 `GraphHostController` 在显式开关开启后承载 HTTPS，默认不注册图控制器。独立安全链只开放已声明的 POST 路径，不接收 Cookie/普通服务令牌作为图认证。
- `GraphRunAuthorityPort`、`GraphReferencePort` 已有 `JdbcGraphExecutionStore` / `JdbcGraphReferenceStore`；`GraphDispatchPort` 负责整图命令持久交接，没有 ALLOW_ALL/内存生产默认实现。工具桥原测试仍保留夹具，新增 cognition probe 已组合真实 JDBC 权威、引用、模型回执、Tool Registry 和 Python 图。
- `GraphCognitionGateway` 组合单次 MODEL、确定性 VALIDATE 与既有 TOOL 网关。复用 `ModelProviderRegistry`（已有 LangChain4j 适配）而非调用旧 `PlatformAgentExecutor.execute()`；不在 Java 中另跑 Agent 循环。仅使用 `GraphToolGateway` 的旧工具测试仍拒绝 MODEL/VALIDATE。

## 身份与数据来源

1. `reserve` 在锁定 Run 的短事务内读取已发布版本、提交人、输入和节点工具子集，冻结 Binding、创建整图命令并标记后端。仅可保留未开始且无历史 Attempt 的 QUEUED Run；不是任意已有运行的迁移 API。内部认证 `claim` 签发 256 位随机能力凭据，数据库只存 SHA-256，重新领取递增 generation 并更换凭据。Worker CLI 与 artifact 领取筛选已接通；`reserve` 不公开，业务版本后端发布路由仍须完成门禁后开放。
2. Python 调用 Gateway 时只把运行能力放在 `Authorization: Bearer ...` 请求头。派发控制面使用独立 Worker Bearer，其加密传输的 Lease 响应/续租交接体携带短期 capability，批量续租也仅在该协议内传递；它们不进入 URL、图状态、持久命令载荷或错误日志。浏览器 Cookie/普通 SERVICE 登录不能代替这两类凭据。
3. Java Authority 从专用图会话表核验租户/Run、generation、命令状态、取消/撤销、数据库时钟租约与 deadline、完整 Binding、原始输入/提交人/流程身份。能力只由这一路径验证，不复用浏览器或 SERVICE 凭据空间。授予上下文提供可信 principal、流程 ID、版本工具集与节点子集。
4. 工具参数绑定 **tenant + Run + bindingDigest + operationId + toolCode + argumentsDigest**；MODEL 先保存原始响应，核验拒绝信号、有效工具集合和参数 Schema 后，将参数引用与模型回执在同一事务提交。没有 HTTP 写引用接口。数据库触发器拒绝覆写/删除；同一逻辑操作参数变化拒绝。运行时不能直接提供参数 JSON、凭据或远程目标。
5. 工具结果复用仍重验当前工具/数据权限；外呼之后、回执记录之后再次核验执行权。取消后可保留成功工具账本，不返回成功完成信号。

ReferencePort 必须原子登记或核对不可变回执引用；引用指向已有工具账本，不成为第二套成功事实。工具已成功而引用登记/回复失败，再次执行同一个逻辑请求会命中原工具回执。原子抢占由现有 `JdbcAgentToolGovernanceRepository` 承担，不使用“先查后调用”代替。

## Wire v1

独立受保护入口承载的固定路径（默认关闭，须显式配置宿主开关）：

| POST 路径 | 输入 | 成功输出 |
| --- | --- | --- |
| `/internal/agent-runtime/v1/authorize` | `binding`、`generation` | `binding_digest`、`generation` |
| `/internal/agent-runtime/v1/invoke` | 上述字段及 `request` | `binding_digest`、`operation_id`、`request_digest`、`receipt` 或 `deferred` |

`request` 为 `kind / operation_id / input_refs / tool`；工具意图仅含 `tool_code / arguments_ref / arguments_digest`。当前工具请求 `input_refs` 必须为空，逻辑 ID 固定为 `tool:N`，N 在版本冻结的工具调用上限内，且引用必须已经获准用于这一逻辑位置。

摘要是 UTF-8、对象键排序、紧凑 JSON 的 SHA-256。Wire 身份结构只包含受限 ASCII 引用、整数和空值，避免两种语言的浮点格式差异；工具参数摘要由 Java 对不可变原始 JSON 字节计算，Python 只转发，不重新规范化业务金额。摘要用于关联与完整性，**不是授权凭据**。跨语言联调已核对双方摘要相同。

请求/响应正文最大 256 KiB；Java 拒绝未知字段、重复键、尾随 JSON、浮点转整数、字符串转 ID 以及非法引用。Python 校验响应和内层回执的三个身份字段，等待响应同样绑定操作，不只校验成功回执。

`GraphCapacityGateway` 额度不足时返回操作绑定的稳定 `capacity:<digest>`、QUOTA 原因与下一次检查时刻；这是等待建议，不预留额度。图先持久化 waiting/interrupt，再由 Worker 关闭检查点会话并提交平台 defer。任一窗口失败都由同一持久命令/最新图状态重建，不能依赖内存定时器。SLICE 是图在调用边界自己产生的预算等待；409/503 不擅自转为已获准的外呼或未登记的完成结果。

## 超时、故障与传输

- 生产只允许验证证书和主机名的 HTTPS；显式测试选项仅允许 `127.0.0.1` / `::1` 明文 HTTP，不允许普通域名或任意私网地址例外。地址是受信部署配置，不是模型/租户输入。
- 不跟随重定向，不读取环境代理设置，不向其他地址转发凭据；不接受压缩正文，关闭异常响应和连接。
- 客户端使用剩余业务 deadline 与 RPC 上限的较小值；每次 socket 读取都重算单调时钟预算，涵盖慢响应头、chunk 行和正文，不让持续滴流重置时限。Java 授权、参数回读及工具调用共享单调预算，最多 120 秒。
- **剩余网络边界**：当前同步客户端的操作系统 DNS 解析不具备可中断硬截止，生产部署还须限定服务发现/解析时限；本轮不是网络分区或高并发连接池验收。当前客户端每次请求独立连接，连接复用与受控异步承载另纳入 G2，不宣称已完成高吞吐。
- `AUTHORITY_DENIED / BINDING_MISMATCH / REFERENCE_DENIED`：拒绝，不能通过模型重答修复。
- `TOOL_IN_PROGRESS`：当前逻辑操作已有所有者，可延期核对；不新建 operationId。
- `TOOL_OUTCOME_UNKNOWN`：不自动重发，进入原有未知结果处置。
- `TOOL_TRANSIENT`、传输中断/超时、503：可按统一预算恢复同一操作，没有客户端内置循环重试；仅有可重试标记不等于已经实现持久退避。
- `TOOL_REJECTED / OPERATION_UNSUPPORTED / INVALID_REQUEST`：明确拒绝。外部异常消息、工具正文及凭据不作为错误返回值。
- `MODEL_IN_PROGRESS` 表示同代调用未结束，不能重复外呼；`MODEL_TRANSIENT` 可交给派发恢复，`MODEL_REJECTED` 不自动重试，`MODEL_BUDGET_EXHAUSTED` 不得换 operation ID 绕过。当前 Python 客户端无隐藏重试。

## 回归与未完成门禁

运行：`scripts/test-langgraph-runtime.ps1 -PostgresBin 'C:/Program Files/PostgreSQL/18/bin' -IncludeJava`。

- Python 总计 **57 passed / 0 skipped**：23 图语义、24 HTTP 边界、6 PostgreSQL 图恢复、3 Worker、1 Java 联调启动测试。
- 该启动测试实际执行 **71 项 Java 测试**：19 工具 Gateway、12 现有 Registry、1 工具跨语言联调、35 持久层/cognition/容量集成、4 架构约束；均无失败/跳过。这里是内嵌关系，不能相加当独立场景总数。
- 联调使用真实 Python HTTP → Java Registry → PostgreSQL 工具账本。三个同租户 Run 分别使用独立能力身份，覆盖丢 ACK 后重放、跨 Run 能力/参数拒绝、过代身份、未实现操作拒绝、6 个并发请求只执行一次。
- V65/V66/V67 在临时独立 PostgreSQL 真正执行；非 owner 角色验证跨租户隔离及无全局治理表/平台 admin 旁路。前置平台表使用最小夹具，**不是全部历史 Flyway 升级验收**。没有修改业务库、旧迁移或已有 AgentVersion。
- 新增 `java_cognition_probe.py`：真实图经 HTTP 连接 JDBC 授权/引用、模型回执和 Registry，使用 PostgreSQL saver；故意丢掉 model:2 和 validate:1 回复，重新打开图恢复及再次冷读，模型固定调用 2 次、工具 1 次、校验回执 1 条。测试模型和版本/Provider 仓储为替身，没有付费网络调用。
- 上述测试不是 P-17 成绩：生产 HTTP/派发 Worker 宿主、真实模型/MCP、Flowable 提交仍未接通。既有 Run 保持 NATIVE，无自动迁移运行。另复测原生执行器/Worker、结果策略、工具桥/Registry 共 66 项通过，不重复相加。

## 持久交接与交叉审查

- 一个 Run 只有一个当前整图命令。V67 准入/领取/调用分配的锁顺序为全局策略行 → Run 行；只更新回执/续租的事务只锁 Run、不反向取得策略锁。租约上限 120 秒且不超过 Run deadline；数据库时钟决定有效期，过期续租不能复活旧身份。
- 传输失败不改 command ID；过期重领仅换 generation/能力，Python 仍核对同一 thread 的权威 Checkpoint。`defer` 原子保存 opaque checkpoint/wait 与 available_at、创建下一整图命令并释放能力。正常等待不创建 Attempt，也不保存 Java nextStep。
- `acknowledge` 只保存候选/拒绝观察结果并释放能力，**不把 Run 标成功、不产生 Flowable 完成事件**。最终接受观察结果前仍需核对候选引用、校验回执和业务权限。
- `inspect` 提供内部持久命令查询，ACK 回复丢失后可核对 command ID、状态与已存观察，不返回能力明文/哈希或图内状态；生产 HTTP 查询接口及自动对账循环仍未接通。引用仓储构造时强制与执行租约仓储共享同一 DataSource，避免误配置造成“校验和写入不在同一事务”。
- 参数/回执写入持有 Run 行锁；检查当前授权前后包围写入，过期时整笔回滚。结果回执必须找到现有 SUCCEEDED 工具账本并核对输出摘要、获准参数引用；不另建工具成功状态。
- 本轮交叉审查修复：回执 Map 键顺序随 JVM 重启变化造成字节比较失败（改为确定性键序）；引用写入中途租约过期仍提交（提交前复核与故障注入）；回执登记未自行核验参数引用（补回读）；续租/交接校验与实际更新间过期（更新条件再次检查数据库时钟）。
- 13 项新增集成覆盖：原子保留及故障回滚、禁止接管历史 Attempt、NATIVE 排除、凭证哈希/串单、过期代次、等待冷重建、观察与业务完成分离、取消/撤销/输入变更、八并发单赢家、不可变引用、成功账本引用、写入期间租约过期回滚、非 owner RLS 读写。

2026-11-12 后续：受保护 dispatch HTTP/Worker CLI、artifact 领取、V68 全平台 Run 创建/重试准入及账户配置/审计已装配，默认关闭；路径、凭据、启动与迁移规范见[宿主手册](../operations/graph-dispatch-host.md)。下一步是可信未知外呼/命令对账、最终业务接纳与容量验收。路由、增量摘要、READ_RESULT 和有限 JSON 修复仍由图节点完成，之后共同验收 P-17/双采购/Flowable 与容量，再允许业务 AgentVersion 发布到 LANGGRAPH。机制与限制见[并发回归记录](../quality/graph-capacity-regression.md)。

部署门禁：V65 只是存储前置，不会自动调用 reserve。必须先完成所有原生 Worker 的后端过滤升级，再开放图版本准入；不能在仍有旧二进制 Worker 时手动改 Run 后端。已有图 Run 不允许回滚给忽略 execution_backend 的旧 Worker，后端切换遵循 G3 排空而非重放转换。

## MODEL 与 VALIDATE 的持久边界（V66）

1. MODEL 的引用清单必须恰好匹配当前 Run 的已提交历史，包含模型和工具回执，不接受漏项、串单、重排或跳号；候选一旦产生，不能再创建 model:N 另答。
2. 数据库原子登记逻辑请求及配置摘要后，事务外仅调用一次现有 Provider 端口。原响应单独提交，再解析决策、原子发布工具参数引用/候选引用与回执。没有 Java 侧工具循环、隐式修复或摘要调用。
3. 同代重复 MODEL 返回执行中。无原响应且执行代次已更换时，V66 允许按预算重试；装配 V67 后还须通过未知外呼隔离门禁，不能只凭换代重发。物理调用次数累计，有原响应则走授权回读，不新占外呼许可或重新裁剪响应。**远端结果未知窗口仍需核对，人工确认后重试仍可能重复计费**，未宣称外部模型 exactly-once。
4. VALIDATE 不读取凭据、不调用模型。与原生执行器共用 `AgentCandidateValidator` 的拒绝信号/输出 Schema/非工具命令规则，使用 `TaskMemory` 从当前 Run 的原始工具回执重新验证主体、决策与 CitationClaim。已验证结果持久化；拒绝不能经重放改成接受。权限撤销不能被缓存成功绕过。
5. 原始响应和已完成模型回执不可改写；读写使用同一有界 Codec，拒绝“可写入但重启后读不出”的响应。校验回执及原因不可覆写。图观察仍须经平台最终验收，不能直接完成 Flowable。

本轮交叉审查进一步修复：重复 JSON 字段覆盖 REJECTED 信号（原生与图共用的结果策略拒绝歧义，普通语法错误仍可进入原生 Schema 修复）；落库 Codec 写/读限制不对称；数据层禁止将 MODEL 当 VALIDATE 提交；网关业务数字采用 BigDecimal，20,000.000000000001 不能在提取输入时先舍入成 20,000。`TaskMemory.Context` 使共享证据门禁不再依赖原生 Executor 命令。新增测试覆盖原响应提交后回执失败、冷恢复预算调小、串单/漏引用、撤权、迟到模型响应、物理调用预算，以及 20,000.01 元无“必要时”证据时拒绝强制审批结论。

**当前能力边界**：MODEL 是行动选择单次调用，还没有等价迁移原生的独立意图路由、增量摘要/原文按需回读和 JSON 修复节点；工具原文整体入参超过预算时明确拒绝，不暗中裁剪。最终证据门禁复用了现有确定性声明契约，不等于通用语义 Grounding 已完成。不能将当前链路作为原生执行器的完整替代上线。
