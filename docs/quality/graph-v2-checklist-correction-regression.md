# v2 必要事实清单与一次纠偏验收

日期：2026-11-20。仅新增显式启用的 CHECKLIST 切片，保留旧版兼容；未切换业务版本或对业务数据库执行迁移。

## 责任边界

Java 从已发布版本 output_schema 的 `x-graph-checklist` 扩展冻结契约到 Run。只接受已授权、参数/原输出摘要校验通过的工具回执作为事实；模型候选与摘要不能补造事实。图仍负责节点、检查点和恢复顺序，Java 负责事实门禁、一次纠偏许可及总预算。

```json
{
  "type": "object",
  "x-graph-checklist": {
    "version": 1,
    "subject_field": "orderId",
    "required_facts": [
      {"id": "approval", "tool": "order_lookup", "value_field": "approved", "expected": true}
    ],
    "allow_correction": true
  }
}
```

输入快照的 input、工具参数与结构化工具输出均须包含同一个非空字符串主体字段。字段名按根对象键读取，不是 JSONPath；expected 仅支持非空 JSON 标量。声明的工具必须属于冻结授权工具集合。启用本适配器后仅开放清单中声明的工具，不支持任意辅助工具自动扩权。

事实状态为 MISSING、SATISFIED、REJECTED、CONFLICT。缺事实、否定事实、跨主体或互相矛盾的历史证据均不能通过；重复回执不制造事实进展。factsDigest 区分事实内容，evidenceDigest 保留来源变化；数值使用精确小数等值比较。VALIDATE 持久保存事实报告，最终 Java 接纳再次使用同一门禁和可信回执。

## 一次纠偏

本切片仅覆盖 V72 可观测动作/结果循环触发，不自动修复被拒绝的候选。仅当契约明确 allow_correction=true、预算有余额，且待放弃模型决策中的所有工具尚未发生物理调用时，Run 锁内原子登记一次许可，返回 `CORRECTION_REQUIRED`。

Python 通过显式 `prepare_correction` 节点持久化 MODEL 意图后才外呼。ID 为 `correction:` 加 binding digest 与触发请求 digest 的组合哈希。Java 拒绝伪造 ID；许可等待期间禁止普通探索及 ACK。原触发请求重放返回同一次纠偏指示，丢 ACK 或换执行进程不新增额度。

纠偏仍走统一 Model Gateway、容量治理及原 Run 的逻辑/物理调用预算。模型原响应先持久化，纠偏结果登记与规范化模型回执同事务提交。事务失败可复用原响应发布回执。原被阻止的模型回执保留，仅其未发送工具组被明确标记放弃；既有业务事实、循环历史、Attempt、deadline 和预算均不清空。

纠偏后仍循环，或触发时没有预算/安全边界，不再申请第二次机会，沿用持久停止和无模型收尾。等待纠偏时进程消失仍受原 deadline 与独立收尾器约束。

## 兼容和剩余工作

V73 新增不可变、RLS 隔离的契约、许可、纠偏结果及事实校验报告表。没有为历史 Run 回填任务契约：v1 和未显式启用的 v2 保持原策略，不自动多调用一次模型。应用升级需要先按正常发布流程部署 V73；本轮只在隔离测试库执行。

尚未完成 PLANNED/Plan 版本切换、JSON 格式修复、压缩与按需回读、真正并行、连续业务无进展阈值、轮询例外或通用语义评委。一次纠偏目前是循环故障切片，未来其他自动修复必须共享同一 Run 额度，不能各自另领一次。严格 Token/费用硬上限、工具内部嵌套外呼计量、完整 P-17 与容量/网络分区验收仍未关闭。不能据此宣称 G0–G3 已完成。

## 验证

规则测试覆盖缺失/正负/矛盾/跨主体/破损证据与重复事实；Python 覆盖两处崩溃后的检查点恢复；PostgreSQL 覆盖许可持久化、非法绕行、预算不足、二次循环停止、否定事实拒绝及纠偏回执发布回滚。继续运行既有 v1/v2 Gateway、预算、RLS、Flowable Outbox/Inbox 回归。

最终结果：`uv run pytest -q` **75 passed（329.87 秒）**。其中一项运行 Java 联调，实际 **165 tests，0 failures，0 errors，0 skipped**；两层计数不重复相加。

Java 分项：架构 4、工具注册表 12、跨语言 Gateway 1、循环规则 8、事实规则 9、PostgreSQL 持久层 89、Tool Gateway 19、流程恢复 6、宿主配置 3、HTTP 控制器 8、真实 Flowable 6。相较 V72 增加 14 项 Java 用例、5 项 Python 用例。

`ruff check`、`ruff format --check`（18 个文件）及 `git diff --check` 通过。Java 25、uv Python 3.12、独立临时 PostgreSQL 18，Maven 使用离线缓存依赖。测试扩大后 Java 包装器等待上限由 360 秒调为 600 秒；生产调用/Run deadline 未改。日志：`C:/Users/email/AppData/Local/Temp/pytest-of-email/pytest-13/test_java_tool_gateway_postgre0/java-gateway.log`（临时文件）。

复现：在 agent-runtime-langgraph 目录配置 JAVA_HOME、MAVEN_ARGS 和 WORKFLOW_LANGGRAPH_TEST_PG_BIN 后运行 `uv run pytest -q`；仅使用独立临时测试集群，不指向业务数据库。
