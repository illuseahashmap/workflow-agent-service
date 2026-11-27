# 版本运行引擎与未知调用核销

V82 将运行引擎配置绑定到 Agent 草稿版本，V83 增加未知外部调用的人工证据审计。它们与 V80 容量配置、V81 持久工具失败收尾组成同一升级序列。旧版本没有配置记录时固定使用 NATIVE；不会因为宿主启用了图运行而自动迁移。

## 发布与提交

在版本管理中打开“运行引擎”。LANGGRAPH 仅支持 PLATFORM_AGENT，必须填写当前宿主已部署 artifact 的 SHA-256，并设置每个 Run 的模型、工具调用预算。宿主未启用或指纹不匹配时拒绝配置或提交。发布后不可修改；新草稿继承上一版本的引擎配置。切换到 MODEL_ONLY 前先将草稿引擎设回 NATIVE。

对应 API 是 `GET/PUT /agents/versions/{versionId}/runtime`，沿用 `agent:manage` 权限及写操作 CSRF 校验。API 的字段、范围见 [OpenAPI](../api/openapi.yaml)。发布冻结、克隆继承和执行模式约束也由数据库实施，不能通过直接更新绕过。

手动提交和 Flowable 派发均通过版本路由。Run、图预算、资源绑定和 dispatch 在同一事务中预约；外层事务失败会全部回滚。全局容量锁在插入 Run 前取得。内部图记账仅在已授权的租户事务内临时使用 worker 数据范围，并在返回前恢复，非超级用户验收覆盖此边界。

暂停立即撤销当前 capability；恢复沿用同一 Run、业务 Attempt 和截止时间，下一次 claim 增加执行代次。取消写入不可逆停止记录，由图完成轮询持久提交 CANCELLED 和完成事件。完成轮询必须运行；不能将“取消请求已接受”解释为外部调用已终止。历史终态图 Run 不支持原地重试扩展预算，应明确提交新 Run。

## 未知外部调用

只有可核实的未发送或已返回事实允许自动释放许可。超时、租约过期、进程退出和取消业务 Run 都不能证明外部调用已终止；未知状态继续占用隔离容量。

平台管理员可使用以下流程：

1. `GET /agent-runtime/call-reconciliations` 查看隔离调用及提案，按最早调用返回最多 200 行。
2. 在 Provider/MCP 对端核对实际请求与终态，保存受控的证据原件及 SHA-256。记录必须能对应页面中的 tenant、Run、operation、request digest 和 generation。
3. `POST /agent-runtime/call-reconciliations` 提交 `token`、`externalRequestId`、`outcome`（COMPLETED/CANCELLED/NOT_ACCEPTED）、HTTPS `evidenceUri` 和 `evidenceSha256`。此步骤只保存不可变提案，不释放容量。
4. 另一位平台管理员独立检查证据，再向 `POST /agent-runtime/call-reconciliations/{id}/approve` 提交相同摘要。禁止提案人自批；身份或状态变化会拒绝操作。审批审计与指定许可释放原子提交，重复审批返回 false。

这是**双人人工核验记录**，不是平台自动验证了外部回执。服务不会访问证据 URL，也不会仅凭 URL 或摘要判定远端状态。没有可独立验证的终态证据时保留隔离。核销不修改 Run 结果，不恢复工具执行，不重新派发流程。旧内部可信 recovery 端口保持兼容，不对浏览器或 worker capability 暴露任意字符串释放接口。

## 验收入口

`AgentRuntimeBindingIntegrationTest` 覆盖冻结、继承、租户隔离、非超级用户提交、权限恢复、外层回滚、排队取消、无模型收尾及双人核销。`GraphCallReconciliationControllerTest` 使用 Spring 安全链验证平台角色与 CSRF。

`AgentBusinessHttpIntegrationTest` 启动独立 TCP 服务和 PostgreSQL，验证真实登录、CSRF、Provider/版本发布、提交、worker 执行及结果查询。模型固定为 MOCK，因此不代表模型质量。

真实 P-17 原生入口保持显式启用；图入口为 `-Dworkflow.rag.live-p17-graph=true -Dtest=P17GraphJointRunTest#liveGraphRunsUseThePublishedFixture`。可加 `-Dworkflow.p17.case=P17-OK` 限定单场景，报告分母按实际选择计算，绝不能将单场景结果记为 7/7。模型凭据从环境读取，报告只保存合成验收的模型响应及 token/耗时，不保存凭据。

## 输出契约兼容边界

正常生成阶段同时提供已发布输出 Schema。原生与图端复用的最终校验现在执行 `enum`、`const`、`additionalProperties`，嵌套对象/数组同样适用，并拒绝尾随第二段 JSON。未配置 Schema 的旧版纯文本输出保持兼容。过去依赖这些约束被忽略的响应会被拒绝或进入已有的一次有界格式修复，不再作为成功业务结果继续路由。这里仍是受限 Schema 子集，并非完整 JSON Schema 实现。
