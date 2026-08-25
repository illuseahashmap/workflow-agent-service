# Agent Runtime Hardening — Traceability and Review

| Requirement | Evidence | Result |
|-------------|----------|--------|
| AC-01 `knowledge_search` 未就绪时不可运行且不自动授权 | `platform-migrations/.../V35__disable_unready_knowledge_search.sql`; `docs/status.md`; `PlatformMigrationIntegrationTest` migration count | PASS |
| AC-02 工具幂等键跨 Attempt 稳定且参数变化可区分 | `AgentToolRegistryTest` stable idempotency and failed-retry conflict tests; stable key is `runId + logicalStepId + toolCode`, while `argumentsHash` is persisted and compared separately | PASS |
| AC-03 Checkpoint 可作为新 Attempt 的恢复输入 | `PlatformAgentExecutorTest.resumesFromCheckpointWithoutCallingPlannerAgain`; worker loads latest checkpoint and persists each progress step | PASS |
| AC-04 修复提示只暴露本次有效工具集合 | `PlatformAgentExecutorTest` asserts repair prompt contains bound tool and excludes hidden tool | PASS |
| AC-05 AgentVersion 发布期验证工具真实性 | `AgentToolCatalogPort` plus runtime catalog adapter; registered/tenant-authorized/schema/mode validation; existing definition service tests | PASS |
| AC-06 工程验证和文档追踪完成 | reactor test, OpenAPI response-model check, OpenAPI route coverage check, `git diff --check` | PASS |
| AC-07 安全边界与流程操作语义 | SERVICE principal-only CSRF exemption; commit-time Redis lock ownership check; explicit reject target; V37 legacy RUNNING recovery | PASS |

## Review decision

PASS（本次范围）

### Findings

- [P2] Docker 不可用时，Testcontainers 集成测试按现有配置跳过；本次领域单测、架构测试和编译均通过。需要在 CI/可用 Docker 环境补跑数据库、Redis 和迁移集成测试。
- [P1] `knowledge_search` 仍是未装配的能力，V35 通过关闭定义和撤销授权确保不会对租户暴露；真实 Retriever、摄取 Worker、Grounding 和离线评测不属于本次修复范围。

## Follow-up requirements

- REQ-NEXT-01：完成真实 Retriever、统一权限策略和应用服务装配，并以数据库迁移显式启用 `knowledge_search`，同时补齐 pgvector/摄取/评测验收。
- REQ-NEXT-02：将 Checkpoint 的上下文和工具结果从受限 JSON 快照进一步演进为版本化上下文/结果引用，支持大结果外置存储、取消恢复和多 Worker 竞争测试。
- REQ-NEXT-03：为工具目录建立不可变版本快照，确保历史 AgentVersion 不受后续 Schema/描述变更影响。
