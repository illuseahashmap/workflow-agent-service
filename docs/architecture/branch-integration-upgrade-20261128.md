# 分支整合的数据库升级与兼容边界（2026-11-28）

统一迁移序列：V79 路由、V80 容量、V81 工具失败策略、V82 冻结执行后端、V83 对账审计、V84 查询扩展策略、V85 主题词指导。V1–V83 的整合分支文件保持原始内容；查询扩展两份文件仅改编号，不改内容和校验和。

## 升级路径

- 新数据库、共享 V78/V80，以及整合分支 V81–V83：使用正常 Flyway migrate。不得开启永久 outOfOrder 或运行 repair。
- 已经应用主分支 `V81__add_query_expansion_policy.sql`（可包含旧 V82）：需要停机的一次性桥接。不要直接启动合并版本。
- 未知校验和、失败迁移、混合或更高版本历史：停止升级，单独核对；脚本不会自动修复。

主分支旧历史的操作顺序（由数据库维护人员在目标环境执行）：

1. 停止所有 API、worker 和迁移进程，备份数据库，保留原版本的完整发布包。用旧发布包执行 Flyway validate，确认整段历史未经篡改。
2. 用具有迁移权限的 PostgreSQL 连接执行 `psql -v ON_ERROR_STOP=1 -f scripts/migrations/bridge-main-v82.sql`。连接信息通过环境变量或密码文件提供，不写入命令历史。此脚本只适用于 public 下的默认 Flyway 历史表。
3. 使用本发布包的迁移目录运行一次 `flyway -locations=filesystem:platform-migrations/src/main/resources/db/migration/platform -outOfOrder=true migrate`。若原环境启用了向量能力，同时附加原有 vector migration location。
4. 清除该临时参数，以 `outOfOrder=false` 执行 validate 和 info；确认 V81–V85 全部成功，无 pending/failed/missing migration，然后才启动服务与 worker。

桥接事务锁定历史表，严格匹配旧 V81/V82 文件名、描述、成功状态与校验和，保存完整原历史、操作者和时间到 `workflow_migration_bridge_audit`。仅将旧查询扩展记录的版本和文件名映射为 V84/V85，保留 installed_rank、校验和、时间和已发布业务数据。后续一次乱序迁移补齐 V81–V83；旧 V81 单独存在时也会正常补齐 V85。中途失败时保持停机：先检查已保存的审计与 Flyway 状态，不重复桥接，不运行 repair，不启动旧包访问已升级数据库；需要回退时恢复整份备份。

此工具只在离线维护时使用，不会增加业务运行时的分支识别逻辑。标准启动依然严格校验。

## 兼容清理

删除 `KnowledgeManagementPort.ProfileView` 三个无调用方的旧响应构造器，统一显式提供冻结索引、评分策略与查询扩展策略，避免新增代码误走默认值。它们不是 HTTP 或检查点协议，不影响旧客户端。

继续保留旧请求省略 queryExpansionPolicy 时的 DISABLED 默认值、已发布配置不可变约束、Graph v1/v2 协议和旧检查点恢复、旧 evidence schema 行为。新证据契约和 RRF_V1 均为显式启用。Provider/MCP 的旧适配器仍有配置入口及测试，本轮不凭名称删除。

OpenAPI 请求与响应都声明 queryExpansionPolicy；CI 检查枚举和默认值，并禁止将此请求字段改为必填。前端允许创建时选择策略，默认关闭；旧后端返回未含该字段的 Profile 仍显示关闭。
