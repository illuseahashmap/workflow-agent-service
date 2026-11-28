# 工具证据预览与回执读取修复

日期：2026-11-28。范围为运行时通用契约修复，不修改查询、语料、金标、检索排序或模型配置。真实模型评测保持暂停，盲测集未读取或修改。

## 原因与证据

暂停前 63 次实际运行中，42 次出现 `AGENT_CHECKPOINT_INVALID`。抽查模型原始响应发现，部分 `READ_RESULT.reference` 使用了 citation ID 或 chunk locator，而非工具结果回执。正常返回的 8 个最终结果均为弃答，7 个应回答的样本表示只有引用元数据、没有正文。此处是诊断记录，不是修复后真实效果评测。

旧 `TaskMemory.observe/merge` 对整个工具 JSON 调用字符串前缀预览（默认 2,048 UTF-16 单元）。引用元数据在正文之前时，前缀可能完全不包含正文，且不是完整 JSON。摘要模型收到的也是这个前缀。数据库里的原始证据并未丢失，但模型可见表示失去了结构和读取线索。

工具回执和证据定位符承担不同职责：前者从当前运行已保存的工具结果中读取数据，后者证明最终答案引用了哪个来源。旧提示只给出平铺的来源列表；读错编号直接使用检查点损坏错误码，混淆了模型请求错误与持久状态错误。

## 实现

- 新增共享 `ToolResultPreview`。小结果逐字保留旧格式；大结果输出有效 JSON 预览，明确 `complete=false` 和可直接复制的 `readNext`（其中 `reference` 为工具回执）。回执只存一份，避免读取信封挤占正文；独立读取索引使用显式 `receiptRef`。
- 对已有 `evidence` 数组保留完整条目，正文与来源引用一起保留；不让前置元数据挤掉正文。保留 `status`、`abstained`，避免把不足证据误呈现为成功。装不下的条目不从中间截断，使用 `omittedEvidence` 和第一个未展示条目的 JSON Pointer 引导回读。所有证据均已展示但其他字段省略时，读取入口仍指向原始结果。
- 非证据工具使用 JSON 包装的有界文本摘录，保留 Unicode 边界。预览不替代原始正文、摘要校验或结果账本。标识和读取信封不能截断；极长标识的原子信封可能超过预览软目标，仍由既有整次模型请求上下文预算控制，不能无限扩张上下文。
- `TaskMemory` 的模型观察与摘要输入共用该预览。持久的回执读取索引明确标出 `receiptRef`、工具及复制式读取请求；不依赖摘要模型记住正确 ID。摘要和回答提示明确“预览省略”不等于“原文不存在”。
- Java/LangGraph 的 `GraphMemory.mergeInput` 使用同一预览；原有 `reference` 保留，索引增加 `receiptRef`，不改变图网关读请求格式。
- 模型给出的非法回执、字段路径或偏移归为 `AGENT_RESULT_READ_INVALID`。Java 直接执行器在原有每步两次读取预算内返回纠正反馈；持续错误后停止。不会把引用 ID 猜测映射成回执，不会读取其他任务的数据，不会重做工具调用。真实检查点损坏仍使用 `AGENT_CHECKPOINT_INVALID`。

## 兼容与边界

不变更数据库表、检查点 `State/Receipt/Fold` 格式、工具原始响应、原始摘要 digest 或 `READ_RESULT.reference/pointer/offset` 请求字段。旧检查点通过原有回执加载并校验原文后，自动得到新的模型预览。小结果与旧版读取请求仍可使用。

不放宽授权、引用验真、截止时间、总上下文或读取次数限制。大条目可能需要分段读取，读取预算不足时仍应安全停止，不保证任意大小证据能在一次模型上下文中完整展示。Graph 侧既有的本地读取失败、预算与恢复机制继续沿用；本轮没有另加一套图侧无限纠错循环。

这次修复不解决所有路由错误，也不修改辅助查询的主题过滤规则。真实答案通过率是否改善，必须在用户恢复评测后重新测量，不能用离线测试宣称已提升。

## 验证

新增测试覆盖：大量前置元数据后的正文与引用关联、过大单条证据的显式外置、拒答状态保留、Unicode 与有效 JSON、小结果原样兼容、旧检查点恢复后读取完整原文、拒绝引用 ID 冒充回执、受限纠错且工具只执行一次、持续错误停止，以及图侧共享预览与旧索引键兼容。

测试不调用真实模型，使用确定性模型替身与隔离 PostgreSQL 容器。

全模块首轮共 361 项，1 项失败、4 项环境条件跳过：原有 `memorySecondSummaryUsesOnlyPreviousNotesAndNewDelta` 发现最小 256 字符预算下重复的回执字段挤占普通工具摘录。修复为信封只保留一份回执，没有放宽测试断言。最终针对受影响路径复测 42 项，全部通过、无跳过，覆盖 `ToolResultPreviewTest`、`TaskMemoryTest`、`CheckpointContextPolicyTest`、`PlatformAgentExecutorTest`、`GraphMemoryTest` 和 `GraphPersistenceIntegrationTest#memory*`。随后按用户要求重跑完整 `agent-engine` 模块：361 项中 357 项通过，0 失败、0 错误、4 项环境条件跳过，BUILD SUCCESS，耗时 4 分 55 秒。图持久化集成组实际执行 148 项并全部通过。跳过项为需外部环境变量的 GraphGatewayIntegrationTest（1 项）和 GraphPythonRecoveryIntegrationTest（3 项），不计入通过数。

变更文件 Checkstyle 与 `git diff --check` 通过。日志位于本地 Codex 输出目录的 `evidence-memory-full-tests.log`（首轮）、`evidence-memory-final-tests.log`（最终专项复测）、`evidence-memory-style.log`。

完整复测日志：`evidence-memory-full-final-tests.log`。本轮仍未调用真实模型 API，未读取或修改盲测集。
