# 图增量摘要与原始结果回读验收（V77）

2026-11-20。本切片承接 V76 计划修订；不表示 G0–G3 或 P-17 完整等价已完成。

## 启用与责任边界

仅新建 state schema 2 Run 从已发布版本冻结以下输出 Schema 扩展；历史 Run 不回填，v1 和未配置的 v2 保留旧行为。

```json
"x-graph-memory": {"version": 1, "inline_characters": 256, "summary_characters": 512}
```

inline_characters 范围 256–2048，summary_characters 范围 256–8192，长度采用 Java 字符串计数。V77 新建不可变、强制 RLS 的 policy、memory、read_intent、read_result 表；摘要保存父引用与累计原始回执来源。原始工具记录不被修改或删除。

Python 图负责 prepare_summary、prepare_context_resume、prepare_read 节点及检查点恢复。Java 负责授权、冻结策略、预算、持久回执、原文读取与最终业务判定，没有新增隐藏的模型调度循环。

## 压缩与恢复

普通模型上下文过大时，宿主在登记逻辑操作和申请物理容量之前返回 CONTEXT_REQUIRED（409）。图保存原请求，按 binding 与输入引用摘要生成稳定 summary 操作，成功后以原逻辑 ID 和更新后的引用恢复。摘要属于 MODEL，消耗同一 Run 的模型次数与物理调用预算。

首轮只摘要新原文的有界预览；后续合并上一份有界笔记与新工具结果的预览，不重复灌入全部历史原文。模型工作上下文包含原始结果索引、非权威笔记、未覆盖的新证据及选中的原文片段。图仍提交完整因果回执链，不能借摘要遗漏工具结果。检查点只保存引用，不保存大文本。

摘要输出、来源与回执同事务发布；原始模型响应可在发布回滚后复用，不再次付费调用。外呼结果未知仍遵循 V75 隔离与可信对账，不因摘要恢复自行放行。原始输入、规则、工具目录或新证据预览仍超出总上下文限制时明确拒绝（CONTEXT_DENIED），不截断必需规则或无限压缩重试。

## 按需读取与证据

模型可请求 `{"action":"READ_RESULT","reference":"原始工具回执","pointer":"/amount"}`。Java 验证同 Run 因果来源后生成内部 graph_read_result 意图；图以 READ_RESULT 调用本地读取节点。读取不重执行工具、不调用模型，也不能申请外呼容量。单段最多 8192 字符；TOO_LARGE、MISSING_FIELD、NOT_JSON 返回有界状态，供模型决定是否缩小读取范围，不返回被默默截断的成功结果。

读取计入逻辑总预算，并与 VALIDATE 共用本地操作上限。每次调用及缓存回放重新校验当前权限；选择摘要或片段不能绕过原始证据授权。金额经严格 JSON Codec 保持精度。ACK 只接收图的观察结果，最终收尾仍独立核验候选与验证回执；摘要和读取产物不能充当最终业务候选；业务校验仍依据完整原始工具事实，即使笔记声称通过也不能覆盖原始否决证据。

纠偏模型选择读取时，读取意图与唯一纠偏完成记录原子提交；发布回滚后复用原模型响应，不重复消耗纠偏机会。读取和摘要不重置业务工具循环检测。规划修订、模型预算、停止和无模型收尾继续沿用既有治理。

## 验收范围

真实 PostgreSQL 回归覆盖上下文重定向前不登记/占容量、原请求恢复、连续两轮增量合并、摘要发布回滚与换代恢复、精确金额读取、原始证据否决、模型预算耗尽后无模型收尾、缺失因果链、跨 Run 引用、产物冒充候选、本地读取失败状态、撤权后缓存拒绝、四表不可变与非 owner 租户隔离，以及纠偏读取的原子提交与幂等恢复。

Python 回归覆盖显式节点、稳定操作 ID、冷恢复和 CONTEXT_REQUIRED 非传输重试。完整联合套件包含旧版网关、规划、修订、持久治理和 Flowable 交接。最终联合运行 **90 项 Python 通过**（其中一项启动 Java 联合套件），内嵌 **209 项 Java 通过**，均无失败、错误或跳过；不把嵌套套件相加为独立 Python 用例。新增 7 项真实 PostgreSQL memory 回归已包含在 Java 总数中。Ruff、18 个 Python 文件格式与差异空白检查通过。

首轮验收暴露测试层级断言不符及启动目录错误：ACK 接收观察结果，不负责最终拒绝；用例已验证后续收尾返回 GRAPH_RECEIPT_INVALID。子进程恢复测试从 agent-runtime-langgraph 目录运行。纠正后重新执行完整联合套件，耗时 512.33 秒。最终日志：`C:/Users/email/AppData/Local/Temp/pytest-of-email/pytest-25/test_java_tool_gateway_postgre0/java-gateway.log`。复现：在 agent-runtime-langgraph 下配置 JAVA_HOME、MAVEN_ARGS 和 WORKFLOW_LANGGRAPH_TEST_PG_BIN 后执行 `uv run pytest -q`。

测试使用隔离 PostgreSQL 18、Java 25 和确定性模型替身。持久化专用夹具测试最小基线迁移；Flowable 套件另执行完整 Flyway 迁移。未修改业务数据库、调用真实模型、发布 artifact 或切换业务版本。

下一阶段为有限格式修复及共享纠偏许可，随后补 P-17 七场景和双采购恢复等价验收。
