# Graph v2 稳定工具意图验收

日期：2026-11-20。第一阶段交付，业务默认开关保持关闭。

## 实现范围

- `/internal/agent-runtime/v2/{authorize,invoke}` 只接受 `state_schema_version=2`；v1 只接受 schema 1。派发协议仍为 v1，认证、租约和绑定校验不变。
- 可信发布代码通过 `reserve(..., schemaVersion=2)` 显式选择新版本；原 reserve 默认 v1。重复 reserve 不允许修改版本，不迁移旧 checkpoint。
- V70 新增独立 v2 模型/校验回执表，启用并强制租户 RLS。v1 字段、序列化摘要、旧回执和校验逻辑保持兼容。
- v2 MODEL operationId 在图准备节点持久化后调用。Java 的存储 ordinal 只用于审计顺序，不决定工具身份。
- **v2 的 `ToolIntent.arguments_ref` 同时是平台分配的稳定工具意图 ID**，格式为 `intent:<UUID>`。Java 在同一事务内发布整批参数引用和规范化决策；Python TOOL operationId 原样使用该引用。Run、租户、binding、工具名和参数摘要仍强制核验。
- 原始模型响应先落库，解析/意图发布失败时复用原响应；整批意图发布事务回滚不留下部分参数引用。已发布但 ACK 丢失时复用原回执和 ID，工具外呼继续使用现有幂等账本。
- 工具可交换合法执行顺序，模型/校验输入回执顺序可变化，拒绝遗漏、重复或跨 Run 引用。初版仍要求完整因果回执集合，未开放任意上下文裁剪。
- 校验绑定具体不可变候选及请求摘要；旧通过回执不能批准新候选。Python 使用 binding/candidate 摘要生成校验 ID，最终业务接纳复核最新候选并复用既有 Outbox。

## 当前边界

v2 多工具使用结构化文本 `{"action":"TOOL_CALLS","tools":[{"tool":"lookup","arguments":{}}]}`，最多 16 项，逐项校验工具和参数。为避免现有单 ToolCall Provider 抽象截断批量结果，v2 不发送 native tool definitions；原生多工具适配仍待实现。单 TOOL_CALL 文本和现有单 ToolCall 响应继续兼容。

当前 Python 图仍顺序消费队列，校验后结束；未新增并行节点、自动修复、Plan、总费用账本、防循环或强制接管。策略绑定现有冻结 AgentVersion/artifact，独立事实版本与动态校验策略版本尚未引入。不能把本次契约交付当作全部 §2.1–§7.1 已完成。

## 验收用例

- 两工具意图、交换执行顺序、重复工具回执恢复，只有两条工具执行账本记录。
- 原始响应提交后、意图发布失败，参数引用整体回滚；重启并换 generation 后复用原响应。
- 模型/工具/校验回执丢 ACK，Python 冷恢复保持 pending operationId；同一 checkpoint 不接受版本变化。
- v1/v2 路径交叉、伪造意图、遗漏证据、旧校验回执替换新候选均阻断。
- v2 模型请求/响应及校验记录不可改写，非所有者角色只能读取本租户记录。
- 保留旧版图、真实 HTTP、PostgreSQL 检查点及 Flowable 完成交接回归。

## 执行记录

测试使用临时独立 PostgreSQL 集群，模型为确定性测试适配器，不调用付费模型，不修改本机业务库。

最终验收：Python `66 passed`（159.95 秒），包含一个启动 Java 联调的入口；其内 Maven 执行 Java 111 项，失败/错误/跳过均为 0。两组数量嵌套，不相加为独立场景总数。

| Java 测试类 | 项数 |
| --- | ---: |
| AgentArchitectureTest | 4 |
| AgentToolRegistryTest | 12 |
| GraphGatewayIntegrationTest | 1 |
| GraphPersistenceIntegrationTest | 60 |
| GraphToolGatewayTest | 19 |
| GraphHostConfigurationTest | 3 |
| GraphHostControllerTest | 8 |
| GraphCompletionFlowableIntegrationTest | 4 |

`ruff check`、`ruff format --check`（19 个 Python 文件）与 `git diff --check` 均通过。

复现入口：在 `agent-runtime-langgraph` 下执行 `uv run pytest -q`，设置 `WORKFLOW_LANGGRAPH_TEST_PG_BIN` 指向独立集群所用 PostgreSQL bin，并提供 JDK 25。此次机器使用已安装的 PostgreSQL 18 与 JDK 25；Maven 通过进程级 `MAVEN_ARGS` 指定本机已有镜像 settings。测试 fixture 自动创建/停止临时集群，不能将测试 URL 指向业务库。

测试修正了工具回执恢复中残留的 ordinal 假设；新版本通过独立身份模式校验历史，原生/v1 默认模式保持不变。业务 v2 开关、部署迁移和真实模型验收尚未执行。

## 部署顺序

先迁移 V70 并部署同时支持 v1/v2 的 Java/Python，再由可信发布流程选择新 artifact 和 schema 2。受限数据库角色须沿用部署授权流程授予新表所需权限。历史 Run 保留 v1；回滚应用前先停止或排空 v2 Worker，保留新表数据，不用 v1 读取 v2 checkpoint。本次没有自动切换业务版本。
