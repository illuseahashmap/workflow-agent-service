# 普通办公采购审批：四份原件与首轮 Markdown 基线

来源：[PROC-POL-01 v0.2 草稿](../../domains/procurement/PROC-POL-01.md) §2.2、§3.1–3.2、§4.1。此包是独立合成评测夹具 v1，不是政策发布、锁定金标或完整制度的替代品。每次评一种格式时，只入库该格式原件，不上传题库、答案、其他格式副本或本报告。

| 原件 | 原生定位 | 原件 SHA-256 |
| --- | --- | --- |
| [审批矩阵.xlsx](sources/approval.xlsx) | 工作表「审批矩阵」；上下限是可读数值，列标题决定下开上闭；合并说明单元格读左上值 | `65959d85060e41b79ff662a0d0ae39bc661104ddc2fa713cc3ef08d0cef85b73` |
| [审批规则.docx](sources/approval.docx) | 正文部件中的标题、段落及原生表格行；不是渲染页码 | `b184db3829ebfe852cf67343914283858594f04c386a0c29078a66e87ff46450` |
| [审批规则.pdf](sources/approval.pdf) | Word 固定版式导出，有文本层，1 页；页框 595.32 × 841.92 pt | `c29eccc8a6e03feef58c5e1a9eb4b52f6990df875650b9233bc06048937b532d` |
| [审批规则.md](sources/approval.md) | UTF-8/LF，原始行号从 1 开始，表头作为独立必要锚点 | `edc5e208a2923d3263187325fb13abe47406d01d4a09fad7d310d7990c44ca6e` |

[9 道问题和预期答案](cases.tsv)用 `requiredAnchors` 指向[Excel、Word、Markdown 定位表](anchors.tsv)和[PDF 区域表](pdf-regions.tsv)。PDF 坐标以左上为原点、单位 pt，页号从 1 开始。这些是人工可复查的试点标注，不冒充当前运行时支持的 Office locator。例：B04 的 20,000.01 元同时需要金额口径、表头、高档行和委员会条件；只命中“财务中心”不能算完整。

## 2026-11-07 实测与复跑

后端基于 `d2deb50`，在独立 PostgreSQL 18.1 数据库执行平台与向量迁移。通过真实管理适配器分别登记 [财务干扰文档](../../domains/finance/FIN-POL-01.md) 与采购 Markdown，运行 DocumentIngestionService、默认切分参数（1200 字符、重叠 120）、候选索引构建和激活；固定最终索引，用 PostgreSQL 关键词检索与 KeywordRetriever 产生 Citation。总计 4 个块：采购 1、财务 3。无付费模型、向量查询或生产租户数据。

| 指标 | 本轮观测 |
| --- | --- |
| 返回的第一条 Citation 来自采购原件 v1 且引文包含全部所需原文行 | B01–B09 均为第 1 名（9/9）；每题返回 3 个候选 |
| 相同查询和索引 ID，改用其他租户后返回结果 | 0/9 次返回任何 Citation |
| 模型回答正确率、最终引用精度、P-17 的真实预算状态 | 未测。B08 命中的是“缺实时记录不能下单”的规则，不是模型成功拒答 |

复跑：仅对**独立测试数据库**设置 `WORKFLOW_TEST_DATABASE_URL`、`WORKFLOW_TEST_DATABASE_USER` 及相应密码（预装 pgvector 并启用 vector 扩展），或使用项目测试容器。在仓库根执行：

```powershell
.\mvnw.cmd -q -pl workflow-boot -am '-Dtest=KnowledgeDocumentImmutabilityIntegrationTest#procurementMarkdownRetrievalBaseline' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

必须看到 9 条 `PROC-PILOT` 结果且测试未跳过。断言要求正确来源版本和完整证据出现在 Top 3，不将本次第 1 名写成永久排名。单块内有全部政策，无法据此判断多块召回、表头重组或模型判断能力。四份原件共用事实，非四个独立业务样本。后续用跨块/近似政策、单位变体及真实只读预算夹具单独测这些能力；PDF 的本次单页正例不覆盖扫描件或跨页续表。
