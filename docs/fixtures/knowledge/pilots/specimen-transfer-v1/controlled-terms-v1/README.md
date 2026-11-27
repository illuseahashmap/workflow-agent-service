# 样品会审：别名找范围，原件证明触发条件

2026-11-10，本地 PostgreSQL 18.1，使用同一份合成原件、金标、查询与 Top5。改动前在隔离数据库重新跑基线；后续在相同配置的独立库重新摄取，不覆盖已发布索引。

- S06 的检索目标是 L7 表头、L10 专项线路金额档及 L12 例会触发原文。3,601 元需要主管复核；只在温度记录出现断点时才提交质量例会。金额本身不证明发生断链。
- 受控词表仅把完整别名作为原生标题的额外索引词，不把“低温”“标本”等单词分别展开，不改变原文、引用、授权过滤或返回条数。词表是随代码审查的版本化资产，并非租户词库管理 API；部署后要重建候选索引、评测、发布新 Profile 才能用于原有文档，旧 Profile 不会就地获得新别名。
- 对明确询问金额审批触发条件的中文问法，必须唯一定位金额档，并在最终证据中保有表头、该行及同一来源的触发原文。仅有“必要时”、条件待补、另一个业务的触发语句，或 TopK 容不下三项，都不足以作答。当前只识别受控句式，不代表通用自然语言推理；该保护按实际检索 query 生效，不代替发布 Agent 的输出约束。
- 工具回归使用真实授权、冻结检索 Profile、PostgreSQL 与 knowledge_search；两个知识库除触发条款外保持一致。完整证据返回 SUCCESS；删除条款或改成待补录，返回 KNOWLEDGE_EVIDENCE_INSUFFICIENT。S05 仍需双方适用依据；H10 三格式不得返回跨业务金额档。

**固定 Top5 实测。** 八题干净取全从 7/8 到 8/8，两档规模下 S06 的顺序均为 L10、L7、L12、L5、L3，没有异表或噪声命中。下表为同机热缓存，延迟单位 ms，每格 64 次、单题 8 次；单题 nearest-rank P95 实际为该组最大值，不代表生产 SLO。

| 块数 | 并发 | 八题 P95（前 → 后） | S06 P95（前 → 后） |
| ---: | ---: | ---: | ---: |
| 14 | 1 | 8.895 → 9.347 | 5.975 → 7.802 |
| 14 | 4 | 12.106 → 10.231 | 8.357 → 10.034 |
| 1,582 | 1 | 11.705 → 11.383 | 12.231 → 7.280 |
| 1,582 | 4 | 13.283 → 13.728 | 13.620 → 10.114 |

[改前原始计时](before-latency.tsv)、[改后原始计时](latency.tsv)、[逐题排序](quality.jsonl)、[H10 三格式结果](h10-quality.jsonl)及[计时](h10-latency.tsv)。H10 每格式四次，P95 分别为 MD 8.102、XLSX 0.639、DOCX 0.721 ms。采购 B04 的跨条款正例也已回归。

**真实模型验收未完成。** 已提供独立 opt-in 用例 liveSpecimenAgentUsesOnlyCompleteEvidence，连接实际 PlatformAgentExecutor 和 LangChain4j 方舟适配器。使用本地配置的聊天模型 deepseek-v4-flash-ga-260731，在请求发送前发生 PROVIDER_PRIVATE_ENDPOINT_REJECTED：方舟域名解析同时含 198.18.0.26。未关闭出站校验，未得到模型答案，未测试 Flowable。应在合规 DNS/出站环境复跑，不用模拟答案或仅有工具 Trace 代替。

复跑：沿用[数据库与固定样本基准命令](../README.md)，测试选择器增加 conditionalAmountNeedsLinkedTriggerEvenWhenMoneyRowSurvives。独立在线测试需显式传入 `-Dworkflow.rag.live-specimen=true` 并选择对应测试，环境提供 ARK_API_KEY 和 WORKFLOW_TEST_ARK_CHAT_MODEL；仅使用合成原件，不连接生产知识库。
