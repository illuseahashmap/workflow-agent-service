# 结构化 PDF 摄取实施设计与开源实践评审

设计日期：2026-11-03；基于代码 `7a92bb5`、选型记录 `cea8117`。性质：待实施设计，不是 D0 试跑、服务部署或业务验收结果。
前置决策：[LangChain4j + MinerU](rag-structured-document-ingestion-design.md)；当前进度仍以[项目状态](../status.md)为准，开放项为[问题 P1-2e](../quality/known-issues.md#p1-2e-结构化-pdf-摄取与图表来源追溯尚未实现)。

## 1. 决策摘要

目标不是另建知识库，而是把现有文本摄取提升为**原件可保留、结构可验证、派生可重建、引用可定位、任务可恢复**的摄取管线。

- MinerU：隔离部署的专业解析器；候选协议为当前官方文档的 4.1 Tasks API。具体发布版本、镜像、模型权重与配置须由 D0 实测后锁定，本设计不使用浮动 `latest` 作为部署配置。
- LangChain4j：继续复用模型/Embedding；通用正文切分优先用现有固定 1.20.1 版本的适配验证。聚合/重排迁移单独做等价性评测，不与 PDF 首次接入捆绑；表格单元格映射不是通用 TextSegment 的职责。
- 平台：原件与派生产物、中立结构、结构感知切分、权限、任务与版本治理。保留现有 PostgreSQL/pgvector 和 AgentRun/Flowable 协议。
- 首期只接 PDF，文本/Markdown 保持原行为。DOCX/XLSX、像素级问答、多模态向量、任意跨页图拼接、自动图表数值推理不随解析器的格式列表自动开放。

## 2. 开源实践依据及采用边界

下列是 2026-11-03 阅读官方资料后的设计归纳，不表示这些项目都提供本平台所需的租户/RLS、不可变版本、租约或发布协议。只借鉴机制，不把 Python 框架或另一套产品整体引入 Java 服务。

| 官方依据 | 可确认的机制 | 本项目采用 | 不直接照搬 |
| --- | --- | --- | --- |
| [MinerU Tasks](https://opendatalab.github.io/MinerU/usage/tasks/) 与 [HTTP API](https://opendatalab.github.io/MinerU/usage/http_api/) | 创建任务、缺失文件上传、状态查询、逐文件结果和取消；服务记录不保证重启保留，原件缓存复用不等于解析只执行一次 | 持久化远端执行回执、平台恢复与结果归档，分离网络任务和索引终态 | 不把远端缓存/任务 ID 当作长期原件或幂等账本 |
| [MinerU 产物契约](https://opendatalab.github.io/MinerU/reference/output_files/) | 输出依入口而异，可含结构 JSON 与图片；资产路径应按实际引用解释 | 保存原始结构与资产清单，再归一化为中立模型 | 不只保留 Markdown、不猜文件名、不把渲染能力等同接口能力 |
| [DoclingDocument](https://docling-project.github.io/docling/concepts/docling_document/) 与 [Chunking](https://docling-project.github.io/docling/concepts/chunking/) | 结构层级/来源与布局；结构切分后按 token 细化，并可丰富上下文 | 原件结构与检索表示分离，表格子块携带必要上下文与定位 | 不改选 Docling、不引入其类型；不启用为了适配长度而丢掉关键表头的策略 |
| [Unstructured Chunking](https://docs.unstructured.io/open-source/core-functionality/chunking) | 解析元素后再切分，优先保留语义元素，过大元素再细分 | 元素边界优先，正文/表格分开处理；来源元素关系单独持久化 | 不依赖未核实版本的私有元数据格式，不以任意字符截断表格兜底 |
| [Haystack FileTypeRouter](https://docs.haystack.deepset.ai/docs/filetyperouter) 与 [DocumentWriter](https://docs.haystack.deepset.ai/docs/documentwriter) | 按文件类型选择转换器；写入端显式声明重复处理策略 | 类型白名单与解析策略分派；同身份同指纹复用、冲突拒绝 | 不使用覆盖写替换不可变已发布数据；文件扩展名不是安全校验 |
| [RAGFlow 配置](https://ragflow.io/docs/v1.0.0-rc2/dataset_configuration) | 按材料类型选择解析/切分，支持父子块及视觉增强配置 | 文档类型决定结构策略；小块召回、受控父块补上下文；视觉派生单独标记 | 不引入第二套知识产品、不启用自动增强/额外模型费用 |
| [LangChain4j RAG](https://docs.langchain4j.dev/tutorials/rag/) | DocumentSplitter、文本 TextSegment、模型适配及 RRF/重排扩展 | 正文通用能力放基础设施适配器，领域只持有中立结构和证据 | 不让 EmbeddingStoreIngestor 接管平台事务、索引激活或历史快照 |

在线资料不是锁定版本的兼容保证。实施前保存所选版本的 API Schema、请求/响应样本、产物与许可摘要；本表中的产品特性须在版本锁定时复核。GitHub CLI 当前未登录，本次只读取公开官方页面，未获取私有仓库或复用其他凭据。

## 3. 当前代码接入点与必须修改的约束

| 当前实现 | 接入问题 | 目标修改 |
| --- | --- | --- |
| `KnowledgeManagementController.createDocument` 接 JSON 正文与文本 SHA-256 | PDF 不应转字符串或伪装正文哈希 | 独立二进制上传回执；原件字节哈希和文本规范化哈希有明确类型 |
| `KnowledgeDocumentStore.loadSnapshots` 依赖正文表及最新文档查询 | 缺结构/资产；上传或解析未完成的新版本不能偷偷改变候选成员 | 显式 IndexBuildManifest，冻结每个成员的文档/派生产物/切分版本 |
| `DocumentIngestionService` 直接 `TextChunker`，对整来源重新切分/Embedding | 接 PDF 后不能每次把所有历史文档再送远端解析；全量列表有内存风险 | 按文档/recipe 生成可复用派生与 ChunkSet，构建完整来源索引时分页读取 |
| `IngestionJob` 按 documentVersion 唯一，持有 owner/Attempt/Lease | 同一原件更换解析配置不能表达新任务；长轮询不能一直占 Worker | 沿用同一持久任务调度框架，区分文档派生和来源索引构建；增加阶段游标、下一次可运行时间和远端回执 |
| `Chunk.of` 的 ID 含文档/正文/序号/文本摘要 | 未绑定解析/切分 recipe，来源区域可能变化但文字相同 | 新结构 Chunk ID 绑定 ChunkSet、父元素及表示摘要；保留历史 ID，不原地重算 |
| `PostgresKnowledgeIndexAdapter` 检查完整来源并原子激活 | 不能把单篇 PDF 解析成功当作整个来源成功 | 保留完整来源检查和原子完成，补 Manifest/recipe/资产/向量完整性与 generation CAS |
| `EvidenceReference.locator` 是字符串；已有 ChunkEvidence | 不能把未实现的页/表/图引用当成已支持类型 | 先新增版本化结构定位解析与安全内容查询；只有语义齐全时才扩展中立 Evidence 子类型，不借用错误 sourceType |

本表是代码修改清单，不宣称上述改造已实现。实施时每一项须由测试和差异报告关闭。

## 4. 三层内容模型与稳定身份

### 4.1 原件、解析、检索不能合成一份正文

1. **OriginalArtifact**：不可变 PDF 字节，SHA-256、MIME、字节数、原件页数、受控存储引用。
2. **ParseArtifact / ParsedDocument**：解析原始 JSON、资产及归一化结构。原件相同但解析 recipe 不同，可以有多个派生产物。
3. **ChunkSet / ChunkRepresentation**：结构切分及面向关键词/Embedding/回答的表示。Embedding 文本不是原始文档，不可替代 Citation 来源。

`DocumentVersion` 表示业务文档的不可变原件版本；换 OCR、模型或 Chunker 不创建虚假的业务内容修订，而是创建新派生版本，再显式建立新索引/Profile。原件确实变化才创建新 DocumentVersion。现有文本版本继续采用原规范化正文哈希；新增 `hashKind` 和原件关联，禁止用 PDF 原件摘要覆盖旧 `content_hash`。

建议稳定身份：

```text
derivationKey = H(tenant, documentVersionId, originalHash, parseRecipeHash)
chunkSetKey   = H(derivationId, normalizedStructureHash, chunkRecipeHash)
chunkKey      = H(chunkSetId, element/row-range, ordinal, representationHash)
embeddingKey  = H(tenant, embeddingRecipeHash, embeddingTextHash)
buildKey      = H(tenant, sourceCode, manifestHash, indexRecipeHash)
```

H 使用带字段名/版本的确定性编码后 SHA-256，不直接拼接可含分隔符的字符串；Manifest 成员排序和序列化规范固定。幂等身份与运行 Attempt/Trace 解耦；同一身份但参数摘要冲突返回 409，不能生成另一条身份掩盖冲突。缓存复用必须在授权之后，首期不做跨租户内容去重或共享缓存，不通过存在性响应泄露另一租户文档。

### 4.2 结构 Schema v1（目标）

`ParsedDocument` 包含 schemaVersion、documentVersionId、originalHash、parseRecipeHash、pages、elements、relations、assets、coverage 与 warnings。持久 JSON 必须经有界 Schema 校验，不能是任意供应商 Map。

- `Page`：原 PDF 零基 pageIndex、展示页码标签、宽高、旋转、内容分类和解析结果。空白页可无元素，但必须有覆盖记录；页数/截断以原件校验为准，不按输出最多页码猜。
- `Region`：pageIndex 与 `[x0,y0,x1,y1]`，归一化至旋转校正后、左上原点、单位区间；同时保留适配转换版本及原始坐标依据。越界、倒置或缺失区域不能伪造。
- `Element`：artifact 内稳定 ID、TEXT/HEADING/LIST/TABLE/FIGURE 等受控类型、阅读顺序、headingPath、regions、父关系、原始元素引用与提取方式。复杂公式/未知块标记不支持，不隐去告警。
- `Table`：表题、原始/归一化单元格、行列索引、跨度、表头角色、单位、脚注及其来源。跨页关系同时保留各页原表元素，合并是版本化派生关系，不销毁原表。
- `Figure`：原图/裁剪资产、图题、来源区域及文本派生描述；每段派生文字带方法/模型/recipe 和源元素。不把生成描述作为人工批准的规则。
- `Relation`：CONTINUES、CAPTION_OF、FOOTNOTE_OF 等显式关系及检测依据；可疑关联进入质量告警，不制造确定结论。

引用目标至少区分 `artifactId/elementId/pageRegions`、`tableId/rowIds/columnIds` 和 `figureId/assetHash`。服务端解析带 schemaVersion 的 locator；不执行任意表达式，不接受客户端文件路径或签名 URL 作为 locator。金标引用原件版本及稳定业务章节/审核结构位置，映射到新 artifact 需审核，不能绑定某次 Chunk ID。

## 5. 持久化、存储与中立端口

以下是逻辑表/接口草案，不是已发布 SQL/API；最终物理拆分以 D0 产物规模实测为依据，不提前写死 Flyway 编号。

| 对象 | 关键字段与约束 |
| --- | --- |
| `knowledge_document_artifact` | tenant/source/documentVersion、kind ORIGINAL/RAW_PARSE/NORMALIZED/ASSET、hashKind/hash、bytes/MIME/storageRef、保留/删除状态；原件关联不可变 |
| `knowledge_parse_recipe` | 不可变 package/container/model/config/normalizer 与能力声明、licenseReviewRef；批准状态可停用，已发布 payload 不可改 |
| `knowledge_document_derivation` | derivationKey 唯一、documentVersion/recipe、产物清单摘要、质量结论；与远端执行代次分开 |
| `knowledge_parse_execution` | derivation、remoteGeneration、endpointConfigRef、taskId/fileId、提交/回执/下载阶段、远端错误摘要、deadline；同代次一个领取者 |
| `knowledge_chunk_set` 与 Chunk 来源扩展 | derivation/chunkRecipe/manifest、表示类型/摘要、元素/行列/区域定位；不可变，发布前完整校验 |
| `knowledge_index_document` | indexVersion、documentVersion、derivation/chunkSet/recipe/hash；租户内唯一成员、索引 Manifest 的权威记录，不再只从有 Chunk 的文档反推成员 |
| `knowledge_ingestion_job` 扩展 | workType、targetRef、阶段游标、availableAt、deadline、取消标记；复用现有租约 owner/Attempt/fencing，事务审计和重试预算 |

所有租户表显式 tenant/source 过滤、强制 RLS；关联使用带 tenant 的复合外键/一致性校验，不能仅凭全局 ID 防越权。recipe 如含平台级模板，模板与租户已批准快照分开，配置只有密钥引用而无明文凭据。

原件与大型 JSON/图片由 `KnowledgeArtifactStore` 流式读写。首个适配器可用持久本地目录（仅单机验收，根目录管理员固定）；生产多实例使用共享对象存储，启用能力前检查跨 Worker 可达性。数据库保存元数据、摘要、受控检索文本与引用，不在 Checkpoint/任务行塞整份 PDF/Base64/结构 JSON。暂存文件→校验→不可变提交→数据库关联；提交前故障留下的孤儿由有宽限期且复核引用的清理任务回收，不能要求对象存储参与数据库事务。原件、资产和派生 JSON 的下载均有输出上限；JSON 深度/数组数、单元格数及压缩包解压后总量也受限，不能仅限制 ZIP 的传输大小。

目标应用端口：`DocumentParserPort` 提供 submit/poll/fetch/cancel 和明确的能力/类型化错误；fetch 为流式有界产物回执，不能一次性返回全部 Base64。`DocumentDerivationRepository` 管理派生身份/远端代次与 fencing；`StructureNormalizer` 校验转换；`StructuredChunkerPort` 产出有来源的 ChunkSet；`IndexBuildManifestPort` 冻结来源成员并验证代次；现有 Embedding/Index/Retrieval 端口继续使用中立 DTO。名称可在实施前统一，但第三方类型不得上浮到领域。

任务仍只有现有 QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED 生命周期；阶段另存枚举，不新增另一套 Worker 框架。workType 至少区分 DOCUMENT_DERIVATION（documentVersion 必填）与 SOURCE_INDEX_BUILD（buildManifest 必填），目标字段通过 CHECK/类型化命令排除歧义。新的幂等唯一约束按工作类型/目标/recipe 建立，替换“同文档永远只有一项任务”的限制；旧终态任务/ID/引用保留，不伪造旧解析 recipe。QUEUED 的等待阶段不占本机线程，但已经提交的远端任务仍占租户解析在途配额；配额需要数据库原子预留/回收，不能仅使用每实例计数器。

## 6. 上传、异步解析与恢复协议

### 6.1 平台序列

```text
受权上传 → 暂存/检测/哈希/配额 → 原件存储提交
  → 短事务登记 DocumentVersion + 派生任务
  → 领取 → SUBMIT → UPLOAD_IF_REQUIRED → WAIT_REMOTE
  → FETCH → NORMALIZE → QUALITY_CHECK → CHUNK → 派生产物提交
  → 唤醒来源索引构建 → 冻结完整 Manifest → 分批 Embedding/写候选
  → 原子验证 Lease + source generation + 完整性 → 激活并完成任务
  → 管理员显式发布新 Profile / AgentVersion
```

WAIT_REMOTE 时持久化远端回执、阶段与 nextPollAt，释放执行线程/任务租约并按 availableAt 重新领取；该等待不增加业务失败次数。保留单调 fencing，但轮询领取计数不能直接作为“已失败 maxAttempts”分母，需要独立失败重试计数。轮询状态不会无限延期绝对 deadline。每次外部动作前后验证任务有效，租约丢失立即退出本地执行链，迟到产物仅能留作隔离垃圾，不可被关联或发布。当前 enum 虽含 CANCELLED，新 Worker 的 terminal 判定、仓储条件和 API 操作矩阵也须覆盖该终态，不能只增加前端按钮。

### 6.2 MinerU 候选接口约束

按当前官方 4.1 文档，候选流为 POST `/v1/tasks` → 按回执 PUT 缺失文件 → GET `/v1/tasks/{task_id}` → 下载指定 file 的结果；取消使用 POST `/v1/tasks/{task_id}/cancel`。**本节是候选协议，不是已锁定服务配置**。首期每个远端任务只提交一份 PDF，避免批量部分成功影响原子文档语义。

平台只发送管理员批准的 recipe 参数、文件名/字节数/哈希与全页范围；不提交本机路径、业务租户隐私或任意 URL 输入。回执先按提交顺序核对再固定 fileId，后续按 ID 关联，不按重名文件名关联。remote completed 仅允许下载，不直接把平台任务置成功。

上传/结果 URL 即使来自解析器也不可信：限定到已批准服务/对象存储 origins 与路径规则，解析、重定向和每次连接均校验地址；上传授权头只用于该回执允许的目标，不能把平台 Cookie/API Key 或源站凭证转发到另一 origin。默认关闭重定向；需要签名存储时单独配置，不放宽全局模型/MCP 出站规则。生产内部解析服务通过专用服务身份、TLS/受控网关和明确网络策略访问；本地回环 HTTP 仅显式 local profile 开放，不能借内部服务需求全局允许私网 URL。

### 6.3 不确定结果与重复计算

| 边界故障 | 平台处理 | 不允许 |
| --- | --- | --- |
| 提交已发但未取得 taskId | 记录 SUBMISSION_UNCERTAIN，短暂对账；若协议不支持幂等查询，只允许预算内新 remoteGeneration，记录可能重复计算并在回执可得时取消孤儿 | 声称远端 exactly-once；虚构 Idempotency-Key 支持 |
| PUT 响应丢失 | 先查询已知 taskId/fileId，仅按有效 upload action 有界重传 | 成功上传后反复 PUT；把 waiting 状态都当缺文件 |
| 平台 Worker 重启 | 按阶段/回执继续查询或复用已提交完整产物，重新领取获得新 fencing | 依赖内存 Future、旧 Lease 或浏览器状态 |
| MinerU 重启/记录消失/结果过期 | 先找平台已归档且 hash/recipe 匹配产物；不存在则预算内重解析、保留失效远端代次 | 将远端缓存当永久原件；在任务失踪时无限轮询 |
| 下载/解压中断 | 丢弃未提交暂存，重新有界获取并验摘要/清单；已提交产物可复用 | 把不完整 ZIP 当解析完成 |
| 取消/撤权/旧 Worker 迟到 | 平台 CAS 取消/拒绝关联，远端取消尽力执行；新代次不能被旧 Worker 取消 | 以远端取消未立即停算为理由接受迟到结果 |

解析是无业务写副作用但有成本的计算；本方案保证**平台产物和索引发布幂等**，不承诺网络不确定或解析服务丢状态时模型计算只执行一次。外部提交与平台记账没有分布式事务，成本重算窗口须可观测且有硬预算。不确定提交也计入租户在途配额与最大提交次数，不能因拿不到 taskId 就释放配额无限补发；配额回收按服务确认或明确超时隔离策略执行。

临时不可用/限流/网络超时仅在绝对 deadline、最大失败次数及成本预算内退避重试；认证/recipe 不兼容/损坏或加密 PDF/质量缺失不盲重试。错误码使用有界稳定编码，脱敏诊断另存。远端长任务不因一次 HTTP timeout 就立即重复创建。各阶段提交游标和产物引用采用 owner/Attempt/有效租约条件写入；过期 Worker 不仅不能写终态，也不能挂接新的产物引用或覆盖远端回执。

## 7. 完整来源索引与版本发布

新文档“上传完成/解析中/审核中”不改变已发布索引，也不能让旧文档从下次构建中无声消失。**成员发布与文件到达分离**：待处理 PDF 不进入可构建成员集合；只有结构质量通过并明确批准纳入来源的版本才更新 source generation。若是撤销旧政策，使用显式停用/撤权而不是依赖上传新文件的副作用。

构建时在短事务冻结来源成员和 generation，成员包括文档版本、derivation、ChunkSet 和模型 recipe。解析、下载、Embedding 在事务外执行；未变化的文档复用完整派生/ChunkSet，Embedding 可按同租户同 recipe 文本哈希复用。批处理必须受条数/总字节/Token 上限约束，有分页游标；不得把所有文档或向量一次加载到内存。

激活同事务锁定当前任务和来源行，校验 owner/Attempt/Lease、取消标记、generation、Manifest 与所有 Chunk/向量/资产完整性，再退休旧索引、激活候选并标记任务成功。CAS 不通过时记录候选过期并重建，不省略新增文档凑成功。索引重建可以新建 IndexVersion；已发布 Profile 永远固定旧索引，不自动随 ACTIVE 指针漂移。

零文本但有图片的 PDF 不得被当作合法空来源激活；要么经已批准 OCR/图题策略得到可检索证据，要么明确失败/复核。允许部分文档发布必须形成**新的显式完整成员快照**和缺失声明，不能在原候选里悄悄丢掉失败成员。首期默认完整覆盖且关键结构错误硬阻断。

## 8. 结构感知切分、检索与 Citation

### 8.1 Chunk 表示

每块记录 parentElementIds、原始区域、sourceTextHash、embeddingTextHash、evidenceTextHash 和 representationRecipe；三种文本可不同，但每段新增上下文要能说明来源。标题、表头、单位和脚注可以重复加入检索表示，不能生成原文没有的规则。

入库统一保存父子关系、同文档/版本内的前后节点、原文范围和结构定位，不以是否属于关键业务决定是否保留这些信息。关联校验可以验证引用存在、类型/范围合法和结构一致，但不能证明任意自然语言条件完整；不设置“先识别复杂语义，才启用上下文”的前置分类器。有限重叠只是边界缓解手段，不是语义完整性保证。

- 正文：按 headingPath/阅读顺序组合相邻完整段落；仅超大元素使用 LangChain4j 正文 splitter，并保留元素内范围映射。无法精确映射时只显示较粗原始区域，不冒称逐字定位。
- 长度：优先按固定 Embedding 模型的 token 预算；若无匹配 tokenizer，先使用经过 D0 实测并标记为保守估算的字节/字符上限，同时处理 Provider 拒绝，不套用英文 tokenizer 宣称中文 token 精准。字符数、token、UTF-8 字节数分别计量。
- 表格：同一表头/条件下按完整行组切分，重复必要表头/单位/脚注；超宽行优先按有业务主键和必要上下文的列组拆分。无法在预算内保留决定性上下文时报告不可索引/需复核，不能删表头、截半行或用模型摘要冒充原数值。
- 跨页：只消费经过校验的续表关系，保留源表和跨页区域；相邻同形独立表不能只因列数相同合并。低置信/未知关联告警，关键规则受影响即阻断。
- 图像：召回图题/OCR/受权派生描述，返回原始资产与来源。无可用文本的图不生成虚假 Chunk；不支持的图像问题返回能力不足，而非据旁边文字猜测。

### 8.2 业务用途、召回表示与扩展策略分离

本节为 2026-11-03 讨论收口，均为待实现设计。按可信业务用途配置两类检索策略，而不是逐句判断语义复杂程度：

| 用途 | 索引与召回 | 命中后处理 | 结果门禁 |
| --- | --- | --- | --- |
| 建议性搜索 | 普通文本/结构子块的关键词、向量或混合召回；是否启用父级表示由评测决定 | 可返回命中块，窗口/父块扩展为可选配置 | 明确来源、缺证与限制，不自动授权推进流程 |
| 驱动型检索 | 子块召回，支持经批准的有界父块召回通道；不能无评测固定为“只索引子块” | 扩展有界父块与邻句、按来源去重，形成证据组；重排启用时按组处理 | 最终 Citation/Grounding、确定性业务规则及风险策略；不能因扩展成功就自动推进 |

用途、索引粒度、窗口范围、父块层级/大小、融合/重排配置及预算必须冻结到版本化 Profile/检索策略，运行时由 AgentVersion/BPMN 的可信绑定决定；模型或普通请求不能将驱动型场景降为建议性策略。当前 Profile 尚未具备上述全部配置，实施时须同步契约和迁移，不把新策略名称冒充已有执行模式。两类策略的租户授权、版本、撤权和删除约束完全相同。

父块可只作原文存储，也可建立独立的有界检索表示；二者是可评测选项。若未建立父块向量/关键词表示，它不会主动参与对应召回。父块整体问题可能无法由任何子块进入 TopK，因此上下文扩展不能替代父级召回能力；也不要求所有祖先或整篇文档全部向量化。建立新表示会产生新索引版本，不能在已发布索引上隐式改变能力。

参考机制：LlamaIndex 的[递归检索示例](https://developers.llamaindex.ai/python/framework/integrations/retrievers/recursive_retriever_nodes/)同时建立多种尺寸子节点和原始大节点的检索引用；[句子窗口示例](https://developers.llamaindex.ai/python/examples/node_postprocessor/metadatareplacementdemo/)将命中句替换为周边原文。[Auto-Merging 源码](https://github.com/run-llama/llama_index/blob/main/llama-index-core/llama_index/core/retrievers/auto_merging_retriever.py)按命中子块比例合并，并非语义完整性判断，不能仅靠该阈值决定关键场景是否带回单位。这里只借鉴设计，不引入 Python 检索框架。

### 8.3 扩展只作用于命中后信息

**先召回命中，再补上下文；扩展不改变已发生的召回，也不能挽救完全未召回的内容。** 驱动型目标链路为：

```text
可信 Profile / 权限过滤
→ 子块及可选父块召回 → 候选融合与有界候选选择
→ 按同一 index/documentVersion/derivation 取父块与邻句
→ 合并原文范围、去重 → EvidenceGroup
→ 可选证据组重排 → 按预算选择完整证据组
→ Evidence / Citation → 最终答案与业务门禁
```

扩展读取再次验证当前权限/删除状态，不跨租户、知识范围、版本或未批准的结构边界。窗口按固定句数/原文范围与 Token 上限确定，不声称能判断每次扩展的语义必要性；父块是有界段落组或表格行组，不默认等于整章/整篇。邻句已被父块覆盖则不重复加入；跨出父块时按批准边界补充并取原文区间并集。不同条件/表行的相同文字不能只按文本哈希合并，不靠重复表头刷 TopK。

### 8.4 证据组与重排输入

`EvidenceGroup` 是目标内部组装契约，不是当前新增的公开 API 或 Evidence sourceType。至少保存：

```text
groupId / schemaVersion
anchorHits[]       命中节点、粒度、召回通道及原始分数
segments[]         命中原文与扩展原文，带角色、元素/行列/页区域引用
rerankText         标题及按原文顺序组装的受控文本
sourceReferences[] 文档/解析/索引版本与所有来源
policyVersion / expansionReasons / tokenCounts / warnings
rerankScore        可空；与原始向量/关键词分数分开记录
```

结构字段用于定位、授权、映射和去重，通常不直接送进 Embedding/重排模型；实际送重排的是 query 与每个 group 的 `rerankText`。排序返回 groupId/分数后映射回完整来源。驱动型场景不应先对孤立子块做激进重排淘汰，再尝试补条件；粗选只控制候选规模，真正相关性重排要能看到已组装的上下文。不能给扩展内容继承一个伪造的独立向量分数，也不能直接混算不同分数尺度。

例如 A 为“金额单位：万元，口径：含税”，B 为“0.2＜金额≤2：直属负责人和部门负责人审批”。命中 B 后，将 A 与 B 组成同一证据组，重排看到两句；A 不作为另一候选与 B 竞争 TopK。若 A 不在扩展范围内，则不能假装已补齐或凭空补单位。证据组重排是本项目设计选择，不是重排模型自动识别依赖的保证；模型打分反映相关性，不证明单位、条件和例外全部正确。[Haystack 重排接口](https://docs.haystack.deepset.ai/docs/sentencetransformerssimilarityranker)提供 query 与 documents 的处理形式，可作为适配参考。

### 8.5 预算、Trace 与验收

只对有界候选扩展，批量读取并去重相同父块/区间；索引存储/Embedding 成本、扩展 I/O、重排调用和最终输入 Token 分别计量。各阶段有独立硬上限，模型上下文窗口不等于可全部消耗的预算。超限时优先减少低优先级完整证据组，不能从组中任意切掉单位、条件或脚注；单组超限按版本化规则缩小结构容器/窗口并显式记录，驱动型无法保留已知必需上下文时拒答/复核，不能静默截断当完整结果。该规则只能保护已知结构条件，不能证明未知语义依赖没有遗漏。

Trace 记录原始命中及通道、扩展前后来源范围、父块/窗口配置、去重结果、重排文本摘要/版本与得分、Token/耗时、舍弃组与预算原因；同一原文被多个节点命中不能算多份独立证据。扩展、重排和答案质量分开报告。

在同一知识快照/身份/模型和预算条件下，对照普通块、邻句窗口、父子块及两者组合；父级召回开关单独消融。固定反例包含单位紧邻/远隔、局部改用元或未税口径、规则跨父边界、子块未命中但父级整体问题命中、父窗口重复、超限及扩展越权。分别量化召回覆盖、最终上下文中的条件覆盖、最终业务结果、引用正确率、Token/成本和 P95；不能用扩展后覆盖掩盖原始召回缺失。以上为待执行测试设计，不宣称已有质量收益。

模型辅助上下文化可作为后续可评测增强：保留原文后，可以先切片，再把原文/受控上下文与切片一起交给模型生成背景；不要求先分类“复杂语义”。生成背景与原文/引用分开保存，固定模型/Prompt/范围，不能把它当作已验证政策或默认启动付费处理；超长文档的受限上下文也不能冒称等价于整篇文档。

Citation 绑定实际返回的证据/单元格及所有来源，而不是笼统整表或随便一个父块。Evidence 文本通过现有中立契约输出；明确的结构化证据语义才使用新子类型，不强行把 Table/Figure 写成 RelationPath 或 External。扩展序列化、OpenAPI、前端与合同测试必须同步。

最终答案逐字段/Claim 校验是否使用本次允许的 Citation、关键单位/条件与否定/例外是否被遗漏；结构解析成功与相似度高都不代表答案正确。完整 Grounding 在 D3/P1-2a 交付，D1/D2 只能报告摄取/检索验收通过。

## 9. 管理 API、权限与前端操作（目标）

端点以现有 `/knowledge` 相对路由为基础；需在实施时同步 OpenAPI、错误响应与客户端类型，不在本轮宣称接口可调用。

| 操作草案 | 语义与返回 | 权限/关键约束 |
| --- | --- | --- |
| POST `/document-uploads`（multipart） | 有界上传、类型/摘要检查、返回 uploadId；不触发发布 | `knowledge:document:manage` + 目标来源写权限；不接任意 URL/路径 |
| POST `/document-uploads/{id}/commit` | 绑定 source/documentCode/version 和批准 recipe，创建文档/派生任务；202 返回 documentVersionId/jobId | 同身份同原件返回原结果，不重排成功任务；不同原件/参数 409 |
| POST `/documents/{id}/derivations` | 同原件新 recipe 的显式重解析，202；不改 DocumentVersion | 受权管理命令；recipe 必须已批准，幂等键与参数摘要分离 |
| GET `/ingestion-jobs/{id}`、POST `/ingestion-jobs/{id}/cancel` | 阶段、进度来源、错误/警告、下一重试时间与允许操作；取消为平台持久命令 | 现有 `knowledge:ingestion:read`；取消新增专用 manage 权限，不能以只读授权执行 |
| GET `/documents/{id}/artifacts`、GET `/artifacts/{id}/content` | 清单与受控流式原件/裁剪/JSON；支持必要 Range 与每次鉴权 | 新增 `knowledge:document:read` 与当前知识范围读权限；管理权限不自动绕过数据权限 |
| POST `/sources/{code}/index-builds` | 选择已批准成员/recipe，生成完整 Manifest 与构建任务 | 专用索引管理权限；不能自动改已发布 Profile |

400 处理非法字段/枚举/页范围，409 处理身份/版本冲突，413/415 处理体积/类型；异步解析损坏/质量失败在 job 中展示稳定业务错误，不以查询 500 表达排队失败。资源不存在/不可见沿现有安全契约统一响应。具体新增权限码与迁移在实施时显式定义，禁止暂借 `agent:manage`。

前端复用现有搜索卡片、分页、状态标签、抽屉/详情组件：上传→任务阶段/失败诊断→结构预览（原件页与表/图定位）→质量检查→来源索引构建→新 Profile 发布→AgentVersion 绑定。分别显示解析状态、质量结论、索引状态、Profile 发布状态，不合并成一个“成功”；进度百分比无可靠分母时显示阶段/已处理页，不编造百分比。新服务凭据/内部 URL 不回显给模型或普通用户，资产响应不可公开缓存。

## 10. 测试矩阵与 D0 可交付物

首批按结构风险建立固定 fixture，而非先按十领域铺几千题。建议 **20 个场景条目**（可各有多份样本；本轮未生成/运行），由有权使用的合成或公开原件组成，每项有 originalHash、recipe、期望结构/定位、审核人和结果处置：

| 编号 | 场景 | 核心断言 |
| --- | --- | --- |
| F01–F04 | 原生中文、多栏、旋转扫描、低清扫描 | 阅读顺序/覆盖/坐标可核对；不可读不冒报成功 |
| F05–F08 | 跨页续表、重复表头、断行、合并表头/单元格 | 续表与原表映射、表头/数值/单位/脚注保持；无错列 |
| F09–F12 | 同形独立表、跨页图题、图表唯一数值、页眉含同名规则 | 拒绝错合并；关联不确定可见；不支持像素推理时拒答；正文/页眉不混淆 |
| F13–F16 | 空白/空图文档、加密、损坏、超页/像素/解压大小 | 明确失败或人工策略；完整页覆盖不等于每页有文本；资源上限生效 |
| F17–F20 | 图片指令/恶意路径/外链、跨租户、删除撤权、故障恢复组合 | 不执行文档指令/路径、不外传授权；无越权；删除后不可见；发布/恢复幂等 |

F20 需细分为提交丢回执、PUT 丢响应、下载截断、平台强杀、MinerU 重启、并发重解析、源 generation 变化、取消与迟到竞争，不用一次 happy path 代替全部恢复测试。F17–F20 的“故障注入”可基于 F01–F12 原件，不需要把所有错误塞进一个 PDF。

测试分层：

1. 单元/属性测试：Schema 边界、坐标转换、row/col span、确定性哈希、recipe/参数冲突、块预算及引用映射；不依赖外部模型。
2. 确定性协议服务：模拟 Tasks/PUT/查询/过期/取消/恶意重定向和超限流，直接验证适配器合同；不能只 Mock Java 方法。
3. 真 PostgreSQL/对象存储集成：RLS、联合外键、领取 fencing、恢复游标、对象暂存崩溃、Manifest CAS/原子激活/重复请求及删除传播。非超级用户验证独立执行。
4. 真实锁定 MinerU：固定 PDF 与配置，记录结构质量、来源覆盖、峰值内存、首/热请求耗时、吞吐及服务重启；模型资源明确 opt-in，不让默认 CI 自动下载数 GB 或产生费用。
5. 浏览器端到端：上传/失败诊断/预览/新索引与 Profile/AgentVersion/Flowable；覆盖无权限和关键结构缺失不能正常推进。D3 必须用真实 Agent 和最终 Citation 验证。

D0 应交付：①版本/镜像/模型/许可锁定清单；②请求/回执/结构/资产样本与 Schema；③fixture 清单和审核金标；④资源/质量原始报告与声明支持范围；⑤API/迁移/端口细化评审；⑥D1 开工结论。**本轮只交付设计，不填写虚构运行数据或已批准 reviewer**。

质量硬门禁：审核集合中的跨租户、静默错合并、关键表头/单位缺失、缺页误报完整、重复激活与迟到污染均零容忍；生产准确率和资源阈值由试点后按风险签署，不能以此小集合的零错误宣称普遍完美。

## 11. 交付拆分、发布与回滚

| 阶段 | 实现切片 | 完成证据 |
| --- | --- | --- |
| D0 | 本设计、版本/资源/协议/样本试点 | 真实报告、支持矩阵、锁定清单；当前未全部完成 |
| D1-a | 原件上传/存储与受权读取、类型化摘要、任务身份扩展 | 上传/幂等/冲突/孤儿清理/权限和迁移测试 |
| D1-b | MinerU Tasks Adapter、远端代次、阶段轮询、产物归档 | 协议故障与双端重启恢复；有界调用/成本 |
| D2-a | 中立结构/资产、结构质量门禁与 ChunkSet | F01–F19 结构/定位/安全报告与版本不变性 |
| D2-b | 完整 Manifest/分页 Embedding/索引原子激活、预览 | 并发/CAS/删除/旧 Profile 保留及检索分层报告 |
| D3 | Agent Citation/Grounding、最终答案/Flowable、运营诊断 | E1/E2 基线和真实浏览器/故障恢复；不得以 D2 代替 |

只用新增迁移，不改 V55/V56/V58 等已发布文件。变更前停旧摄取 Worker，数据库声明 Worker/任务协议最低版本；新版任务不允许被旧 Worker 领取。历史文本内容/Chunk/索引/Profile 保留原值，已有结果不伪装为新结构模型。迁移数据需标记 legacy provenance，缺原件/recipe 不编造回填；新结构索引通过显式重建进入。

功能开关控制新 PDF 创建/解析/发布，不影响已有文本读取。失败时关闭新任务入口与发布、保留诊断并停用候选；运行回退选择上一已发布 Profile/AgentVersion，不恢复覆盖数据库或修改旧索引。数据库升级通常只前进，应用回退必须确认旧 Worker 不会领取新任务；不可将“代码能启动”视为 schema 向后兼容。

## 12. 设计自查与未决事项

本设计已在文档层检查：与现有原件哈希/任务唯一性/Chunk ID 的差异、异步轮询计数、来源成员一致性、双方重启、远端不确定结果、外链凭据、防越权、最终答案边界和不可变迁移。2026-11-03 讨论补充检索用途分层、父级召回可选性、命中后扩展、证据组重排及 Token/评测边界，撤销“只索引子块是固定首版结论”和“有限重叠能保证语义完整”的推断。该自查不等于代码或质量通过。

D0 仍需用真实证据定案：MinerU 精确版本/运行后端与模型；CPU/GPU 与容量；标准/高质量档的支持矩阵及是否允许外部 LLM 辅助；生产对象存储实现；最终物理 Schema/版本化权限码；结构审核角色和阈值。默认不自动调用云视觉模型、不自动付费、不静默降档；无法满足预算或质量时缩小并明确支持范围，而不是把不完整结果当成功。
