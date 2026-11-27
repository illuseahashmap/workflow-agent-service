# Runtime、路由与运营收口设计

## 路由边界

模型输出 `AgentRouteDecision.v1`，平台依次执行：

```text
AgentVersion 工具集 ∩ BPMN 节点工具集 ∩ 租户授权
→ Structured Output 路由建议
→ 模式/工具一致性校验
→ 冻结 routeTools 到 Checkpoint
→ 进入模型/工具循环
```

LangChain4j 只负责 Provider JSON 模式和协议；平台负责 Schema、授权、失败策略、持久化与恢复。
RAG 的主体知识范围仍由 `KnowledgeAccessPolicy` 在工具执行时二次求交，模型不能扩大范围。

## Heartbeat

实例使用一个可配置、上限 32 的共享 `ScheduledThreadPoolExecutor`。Run 只注册续租任务，不创建
独立循环线程。取消任务从队列移除。指标记录计划时间与实际开始时间之差、数据库续租耗时和队列深度。

## Checkpoint

v2 保存 Schema 版本、上下文摘要、恢复游标、工具结果引用和冻结路由工具集。超限内容采用确定性
首尾保留，中间以原内容 SHA-256 标记替换。权威工具执行状态仍在审计账本；后续大结果只保存对象或
Evidence 引用，不把模型摘要当作业务事实。

## 运营

Prometheus 使用短期平台管理 Token 拉取受保护指标并转发 Alertmanager。Token 只能来自工作区外文件。
Agent 自动恢复结果以 SYSTEM 主体写入现有流程操作审计，不伪造人工操作人。
