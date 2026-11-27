# Graph v2 Run 预算、持久停止与无模型收尾

日期：2026-11-20。继 v2 契约及稳定工具意图之后的治理切片。仅新显式 v2 reservation 使用；未启用业务发布入口，未执行本机业务库迁移。

## 已实现边界

- `GraphRunBudgetPort.Policy` 在 reserve 事务中冻结 Run 的逻辑动作总数、外呼预留总数及校验次数。默认动作/外呼总数均为 `maxModelCalls + maxToolCalls`，校验次数为 `maxModelCalls`；可信宿主可用新增 reserve 重载收紧总数。MODEL、TOOL 各自还受 binding 的分类上限约束。VALIDATE 不外呼，不消耗外呼总数，但消耗独立校验额度。
- `tenant + run + operationId` 唯一登记逻辑动作并校验整个请求摘要。缓存回放不新增逻辑或外呼记录。新操作即使参数相同也计次；同 ID 改参数被拒绝，不写停止裁决。
- 每次受控模型适配器调用/工具执行入口先提交独立 UUID `callAttemptId`。工具在幂等账本成功领取后、实际调用工具之前预留；缓存和正在执行的工具不预留。所有 generation、片段和业务 Attempt 共用同一 Run 账本。预留保守累计，不因失败、重启、超时或未知结果退回。
- 模型返回的 input/output Token 是不可变用量事实；迟到事实允许记录，但不恢复执行权，不为停止后的答案签发可用回执。工具记完成事实及零模型 Token。未知预留保留，未伪造实际 Token。
- 超出动作、校验、模型、工具意图或外呼预算先提交不可变停止记录，再返回 `RUN_STOPPED`，避免异常回滚停止裁决。旧代次不能提交停止；续租、再次领取、调用、回执恢复及图 ACK 都不能绕过既有停止。
- Python v2 将可信 `RUN_STOPPED` 记为终止检查点 `STOPPED`，清除待交付候选引用，关闭检查点会话后退出，不 ACK 成功，也不再进入普通恢复循环。Java 是停止权威；Python 无法连接或落盘不影响平台最终收尾。
- Java 完成轮询独立扫描 v2 总 deadline（包括排队和暂停），不要求图 ACK、模型、校验回执或检查点锁。Run、已有 Attempt、状态历史、完成事件 Outbox、停止完成标记在一个事务中提交。事件发布失败全部回滚业务完成并退避，已提交停止裁决仍保留。
- 正常候选接纳与停止收尾使用相同 Run 行锁。停止已提交时不能成功；业务成功已提交时停止收尾只能丢弃，不覆盖终态。完成事件继续由既有 Inbox 和冻结的流程失败策略处理，不造最终答案、不做成功输出映射。排队结束无 Attempt 时使用既有事件的 `attemptId=0` 哨兵，仅对失败/超时接受，禁止无 Attempt 成功。
- 不自动释放未知外呼 Permit；业务终态、远端真实结束、检查点锁释放是不同事实。停止收尾失败的 Run 不阻塞其他候选完成。

## 兼容与迁移

新增 V71；不修改已发布 V65–V69。v1 的 binding、wire、ordinal、预算行为和完成入口保留。V70 是上一切片尚未发布的 v2 迁移，也未修改。

V71 对升级前已经存在的 v2 reservation 记录 `GRAPH_BUDGET_MIGRATION_REQUIRED`，保守停止后无模型收尾。旧 v2 没有完整外呼预留账本，不能把累计消耗按零初始化；这不是透明恢复旧 v2 的承诺。v1 不受该回填影响。正式启用前须先排空试验 v2，或另做可信账本迁移；不得删除停止记录、修改上限后恢复原 Run。新增账本、用量和停止/完成记录均强制 RLS、禁止修改或删除。

V71 同时以 Run 更新触发器防止旧运维入口自动延长 v2 deadline、清空活动 Attempt 或复活终态。原生/v1 行为保留；v2 图分片交接/代次接管不改这些字段。旧式人工 resume/retry 若要求上述变更会明确失败，不能假装恢复成功；新增人工延期/追加预算接口仍待独立授权协议。

预算端口是可信 Java 内部接口，未提供让图客户端变更上限或自行解除停止的 HTTP API。现有 Java authority adapter 同时实现预算端口；只实现旧 authority 的 adapter 只能服务 v1，v2 默认拒绝。

## 明确未覆盖的能力

这里的外呼额度是模型适配器及工具执行入口的预留包络，不能当作任意工具内部每个 HTTP 请求的精确计数。当前 OpenAI 适配器关闭 SDK 隐式重试；未来嵌套模型、工具内部重试、摘要/路由/纠偏节点必须接入同一预算，不能绕过端口。

Token/费用**严格硬上限尚未实现**：目前缺少冻结可信计价、可验证输入 Token 上界和 Provider 受控最大输出契约。用量记录不等于发送前 Token/费用预留，本切片不声称费用绝不超限。语义无进展检测、一次纠偏额度、新 Plan/摘要/并行图，以及安全强制接管仍是后续阶段。

## 验收

测试使用独立临时 PostgreSQL；Flowable 测试使用全部 Flyway 迁移、真实 Outbox/Inbox 与本地流程引擎。无真实模型费用，无业务库写入。

覆盖：同 ID 重放/改参、冻结策略不可变、最后一份预留并发争抢、跨代次未知预留、校验与工具意图超限、停止后缓存/续租/ACK 拒绝、迟到用量、排队/暂停 deadline、双实例只发布一次、Outbox 回滚与退避、未知 Permit 不释放、RLS，以及真实人工复核流程和 v1 回归。

验收结果：

- Python 全套 `uv run pytest -q`：**70 passed**（199.63 秒），其中 1 项内嵌 Java 测试，不能与 Java 数量相加当作独立用例总数。
- 最后新增 Run 生命周期数据库保护后，重新执行 `uv run pytest -q tests/test_java_integration.py`：**1 passed**（191.55 秒）；实际 Java **133 tests，0 failures，0 errors，0 skipped**。
- Java 分项：架构 4、工具注册表 12、跨语言 Gateway 1、PostgreSQL 持久层 74、Tool Gateway 19、流程完成策略 6、宿主配置 3、HTTP 控制器 8、真实 Flowable 6。
- `uv run ruff check src tests`、`uv run ruff format --check src tests`、`git diff --check` 通过。
- 环境：Java 25、uv Python 3.12、临时 PostgreSQL 18；Maven 使用本机已缓存依赖及离线镜像设置。最后一次 Java 日志：`C:/Users/email/AppData/Local/Temp/pytest-of-email/pytest-10/test_java_tool_gateway_postgre0/java-gateway.log`（临时文件，不作为长期归档）。

非 owner 角色更新另一个 schema 的 Run 也纳入回归；触发器使用 `TG_TABLE_SCHEMA` 定位预算表，不依赖调用者 `search_path`。新增旧入口回归确认：v2 deadline/活动 Attempt/终态不可重写，原生 Run 的既有延期仍有效。

