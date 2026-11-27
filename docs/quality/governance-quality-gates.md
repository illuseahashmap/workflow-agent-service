# 治理代码质量门禁与迁移状态维护

2026-11-25。本轮整改对应代码规范、关键覆盖、迁移退出条件和文档状态四项评审，不代表业务后端已切换。

## 代码规范

继续执行原 Checkstyle 规则，不增加忽略项，不排除旧执行器或测试文件。格式修复包括显式导入、每行单语句、单变量声明、控制分支括号和长行拆分。图联合验收在运行测试前执行同一规则，避免专项测试通过而完整 verify 才发现规范违规。

完整后端 CI 仍执行 Maven verify、SpotBugs、全模块 JaCoCo 基线和基础设施测试执行检查。专项图验收与完整后端门禁不是同一结论。

## 关键治理覆盖

全模块历史最低门槛保留为行 25%、分支 15%，避免用未测量的全局上调掩盖差异；另新增逐类门槛：以下每一个类均要求行覆盖至少 90%、分支至少 70%，不能由其他类的平均值补足。

| 类 | 治理责任 | 回归重点 |
| --- | --- | --- |
| GraphCognitionGateway | 当前权限和原始证据授权、模型/读取/校验边界 | 撤权、跨 Run、原文否决、摘要预算、非法请求 |
| JdbcGraphExecutionStore | Run 身份、预算与持久停止 | 旧代次、预算耗尽、停止后拒绝、幂等回执 |
| JdbcGraphCallCapacityStore | 物理容量与发送屏障 | 未发送回收、在途隔离、返回释放、竞争与接管 |
| JdbcGraphCompletionStore | 最终业务验收和交接 | 非法候选、原子提交、断连回滚、重复交接 |

门槛定义位于 根 pom.xml 的 check-governance，由必跑的图联合验收显式调用；普通 backend verify 独立保留全模块门禁，不拿未启用 PostgreSQL 的单测计算图治理覆盖；不得以跳过该执行或排除类来消除失败。图联合验收开始前清除旧 JaCoCo 数据，只使用本轮实测；生成 XML 后检查四个目标类均存在，缺失报告或类直接失败。该联合套件应独占工作区，不能与其他 Maven 任务同时写 target。

门槛是下限，不代表所有安全路径已覆盖。新增授权、预算、恢复分支必须同时提供失败或竞争场景；提高覆盖优先补真实边界，不编写仅复述实现的测试。后续提高门槛应提交同次测量及新增用例，不为迁就失败降低门槛。

发布必须同时检查 Backend Verify 和 LangGraph Runtime Verify 的成功结果；仓库保护应将两者均列为必需检查，本轮不改远端分支保护设置。

CI 单独归档图验收的 Surefire、Checkstyle 和 JaCoCo 报告。扩大超时预算以容纳真实 PostgreSQL/Flowable 测试及报告生成，不能用省略数据库测试缩短时间。

## 状态维护与退出

[当前状态](../status.md)是实现状态的唯一入口；[问题清单](known-issues.md)只跟踪剩余风险，历史验收记录保留当时结论。阶段变化的 PR 必须同步当前状态、对应问题及证据链接，禁止在历史记录上覆盖原始结果。

增量摘要与 READ_RESULT 已实现，仍需完整图等价验收；V78 最终答案有限格式修复已实现，后续证据见[验收记录](graph-format-repair-regression.md)。详细的受控发布、原生排空及删除重复实现条件见[迁移退出清单](../operations/langgraph-migration-exit.md)。这些是发布前置条件，不能把本轮文档整改当作完成迁移。

## 本轮验证

本轮验收：

- 图联合套件：90 项 Python 通过，其中一项启动 209 项 Java；Java 零失败、错误或跳过。实际执行独立 PostgreSQL 与完整 V1–V77/Flowable 迁移。耗时 613.74 秒，日志 `C:/Users/email/AppData/Local/Temp/pytest-of-email/pytest-29/test_java_tool_gateway_postgre0/java-gateway.log`。
- 后端补充 verify：排除上轮已通过的 GraphPersistenceIntegrationTest、GraphGatewayIntegrationTest 和 GraphCompletionFlowableIntegrationTest 三个套件，其余 461 项中 436 通过、25 跳过，零失败/错误。Checkstyle 全模块零违规，SpotBugs 与 JaCoCo 检查通过。日志 `C:/Users/email/AppData/Local/Temp/workflow-remediation-verify.log`。两轮测试有重叠，不相加为独立测试数量。
- 25 项跳过涵盖 Docker/Redis、部分认证/流程事务/迁移、显式 P-17、原生双采购与 RAG/向量验收；本次不冒充这些生产或容器场景已通过。完整容器门禁仍开放。
- 门禁正反验证：在临时独立 POM 中使用同一类清单和真实覆盖数据，90%/70% 成功，100%/100% 明确失败；证明规则确实匹配目标类，不是空匹配通过。日志目录 `C:/Users/email/AppData/Local/Temp/workflow-coverage-gate-j9k6pgf5`。
- 最终逐类 90%/70% 门槛显式执行成功，Ruff 与差异空白检查通过。初次图套件清除了旧覆盖数据；后端补充执行在相同代码上追加有效测试数据。下表只采用追加前的独立图套件结果。

| 类 | 行覆盖 | 分支覆盖 |
| --- | ---: | ---: |
| GraphCognitionGateway | 94.21% | 75.27% |
| JdbcGraphCallCapacityStore | 99.56% | 74.47% |
| JdbcGraphCompletionStore | 92.31% | 80.43% |
| JdbcGraphExecutionStore | 97.50% | 76.65% |

未启动业务服务、修改业务数据库或推送提交。2026-11-25 验收时 Docker 未启动，独立 PostgreSQL 专项不得冒充完整容器门禁。2026-11-26 的 Docker 与格式修复验证另见[后续验收](graph-format-repair-regression.md)。
