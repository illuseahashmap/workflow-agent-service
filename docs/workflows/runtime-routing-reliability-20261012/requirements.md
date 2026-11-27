# Runtime、路由与运营收口需求

变更 ID：RRR-20261012

## 目标

1. 复用 `PLATFORM_AGENT` 既有规划调用生成版本化结构化路由，不固定增加模型调用。
2. 消除单线程 Heartbeat 的全局头阻塞风险，并暴露 Lease 安全余量相关指标。
3. 将 Checkpoint 尾部截断升级为可校验、兼容迁移的版本化上下文。
4. 提供 Prometheus→Alertmanager 接线模板，并把 Agent 自动处置写入流程审计。

## 验收标准

- AC-01：路由支持 `DIRECT/RAG/MCP/RAG_MCP/ABSTAIN`，只能引用有效工具交集。
- AC-02：非法路由最多修复一次；ABSTAIN 或第二次失败进入既有失败/人工策略。
- AC-03：路由版本、原因和冻结工具集可由 Step/Checkpoint 还原。
- AC-04：一个阻塞续租不阻塞调度池中的其他 Run；调度延迟、耗时和队列深度可观测。
- AC-05：Checkpoint v2 可校验摘要、兼容 v1，超限压缩保留不可变前缀和最新证据。
- AC-06：Agent 节点的成功、人工复核、空结果继续和运维保持均可在流程审计中查询。
- AC-07：Alertmanager 配置可由 Compose 解析；真实通知回放必须在 Docker 可用环境验收。
