# P0 生产基线追踪与审查

更新时间：2026-08-26

## 验收追踪

| 验收标准 | 结果 | 证据或剩余工作 |
| --- | --- | --- |
| AC-P0-01 集成测试缺失或跳过必须失败 | CONCERNS | `mvn verify -Dspotbugs.skip=true` 构建成功，但本机 Docker 不可用导致必需 Testcontainers 套件跳过；`scripts/check-integration-test-results.mjs` 已按设计返回失败。需在启用 Docker 的 CI/主机复跑。 |
| AC-P0-02 租户隔离的审计查询 | PASS（代码级） | 新增查询应用服务、JDBC 适配器和权限 `workflow:audit:read`；租户来自可信上下文，不接受请求租户字段。真实 PostgreSQL/RLS 验证待容器环境。 |
| AC-P0-03 结构化过滤、分页和倒序 | PASS | 查询支持事件类型、流程实例、Trace ID、时间范围、页码和页大小；按 `occurred_at DESC, id DESC` 排序；服务单测通过。 |
| AC-P0-04 核心操作可追溯且不泄露敏感数据 | PASS（代码级） | 查询 DTO 仅暴露操作证据字段，不返回凭据或业务变量；既有写入审计链路保持不变。完整事件覆盖待集成测试。 |
| AC-P0-05 OpenAPI 与错误边界 | PASS（结构级） | `docs/api/openapi.yaml` 已描述新增接口、参数、分页响应；Redocly 校验 API 有效。仓库历史接口仍有 59 条既有 warning，未在本轮扩大范围。 |

## 验证记录

- `mvn verify -Dspotbugs.skip=true`：SUCCESS；各模块单元测试、架构测试、Checkstyle、JaCoCo 通过。
- `npx @redocly/cli lint docs/api/openapi.yaml`：API valid；存在既有 warning。
- `node scripts/check-integration-test-results.mjs`：按预期拒绝跳过的五组基础设施集成测试。
- 浏览器验证：本轮仅涉及后端查询 API、迁移和契约，未执行前端浏览器测试；应由 API/集成测试验证。

## 审查结论

当前 P0 已完成代码级收口，但不能宣称全部生产闭环完成。剩余阻塞是本机 Docker 环境和 P0-2 的告警、失败运行运维视图；不得通过放宽门禁或跳过测试掩盖。
