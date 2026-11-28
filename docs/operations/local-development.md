# 后端独立接手与初始化

维护日期：2026-11-28。本仓库包含 Java 平台与 Python LangGraph；完整产品 Compose、前端及统一入口在根仓库 `workflow-agent/docs/AI-BOOTSTRAP.md`。本文件在只克隆后端时也可使用，不依赖个人电脑目录。

## 环境与依赖安装

- JDK 25，JAVA_HOME 指向 JDK 根目录；用 java -version 验证。
- Git；PostgreSQL 18 和 Redis。需要向量功能时 PostgreSQL 必须安装 pgvector。
- Maven 通过仓库 Wrapper 下载锁定的 3.9.12，无需全局 Maven。Windows 执行 `.\mvnw.cmd`，其他平台 `bash ./mvnw`。
- 仅启用 Python Worker/其验收时需要 Python 3.12–3.14、uv，执行 `uv sync --project agent-runtime-langgraph --frozen`。无需 LangSmith 账号。
- Docker 是运行隔离数据库测试的推荐方式；首次 Maven 构建需要可用的依赖下载网络。

先检查 `git status --short`、现有服务和数据库。不要为安装依赖覆盖他人的工作区，或删除旧库绕过启动失败。

## 数据库与基础数据

使用现有数据库实例时，以数据库管理员连接 postgres 数据库，执行仓库提供的本地建库脚本：

```sh
psql -h 127.0.0.1 -p 5432 -U postgres -d postgres -v ON_ERROR_STOP=1 -f scripts/local-postgres-init.sql
```

它仅在缺失时创建 `workflow_agent` 登录角色和同名数据库，新角色使用本地开发密码 `workflow_agent`。重复执行不会重置已有角色密码，也不会接管已有数据库所有者。连接密码交给 psql 提示或本机密码文件；已有账号的实际密码必须由环境管理员提供。此脚本不用于生产，也不创建平台业务表。

平台表、默认租户、角色权限、内置工具及治理基础数据由 `platform-migrations/src/main/resources/db/migration/platform` 的 Flyway 脚本自动初始化。当前统一迁移至 V85，不要逐条手工跑 SQL。`local` profile 允许 Flowable 首次建表；普通 profile 中默认禁用自动更新。已经应用过旧主分支查询扩展 V81/V82 的库，必须先按 [分支升级说明](../architecture/branch-integration-upgrade-20261128.md) 处理，不能直接 repair。

管理员不是 SQL 明文种子：由 PlatformAdminBootstrap 使用配置创建并绑定角色。已有同名用户不会被重置密码，但启用 bootstrap 会为该用户名授予平台管理员角色，所以只填受信管理员，首次初始化后可关闭 WORKFLOW_BOOTSTRAP_ADMIN_ENABLED。

必要业务参考数据应新增版本化迁移并提供升级测试；演示文档、合成采购单与模拟回执留在 `docs/fixtures`，由验收测试在一次性数据库导入。不要直接 INSERT 已发布 AgentVersion、Run、调用回执或检查点。

## 配置（Java 不自动加载 .env）

`.env.example` 是键名模板；使用 IDE 的环境变量加载器，或在启动 Java 的同一终端逐项设置。bash 用 `export KEY=value`，PowerShell 用 `$env:KEY='value'`。不要把秘密写入 Java 参数、仓库或日志。

| 变量 | 本地值 / 规则 |
| --- | --- |
| SPRING_PROFILES_ACTIVE | local；需要向量且已配齐时 local,vector |
| WORKFLOW_DB_URL | jdbc:postgresql://127.0.0.1:5432/workflow_agent |
| WORKFLOW_DB_USERNAME / PASSWORD | 与目标实例实际账号匹配；脚本新建账号为 workflow_agent |
| SPRING_DATA_REDIS_HOST / PORT | 实际 Redis 地址，通常 127.0.0.1 / 6379 |
| WORKFLOW_MASTER_KEY_BASE64 | 32 随机字节的 Base64；首次生成并妥善保留 |
| WORKFLOW_AUTH_TOKEN_SECRET | 独立随机签名秘密，至少 32 字符 |
| WORKFLOW_BOOTSTRAP_ADMIN_ENABLED | 首次 true，后续可 false |
| WORKFLOW_BOOTSTRAP_ADMIN_USERNAME / PASSWORD | 受信用户名；首次创建密码至少 12 字符 |
| WORKFLOW_GRAPH_ENABLED | 首次 false；普通 Java/NATIVE 启动无需 Python |

凭据加密可单独使用 WORKFLOW_AGENT_CREDENTIAL_KEY_BASE64；默认继承主密钥。不得丢失或随手更换主密钥。迁移与运行角色分离时使用 WORKFLOW_MIGRATION_DB_URL/USERNAME/PASSWORD，生产运行账号不能靠超级用户绕过 RLS。`local` 允许本机 HTTP、开发出站和非 secure Cookie，不用于公开部署。

## 构建与启动

Windows：

```powershell
.\mvnw.cmd -pl workflow-boot -am -DskipTests package
java -jar workflow-boot/target/workflow-boot-0.1.0-SNAPSHOT.jar
```

Linux/macOS：

```sh
bash ./mvnw -pl workflow-boot -am -DskipTests package
java -jar workflow-boot/target/workflow-boot-0.1.0-SNAPSHOT.jar
```

确认 `http://127.0.0.1:8080/actuator/health` 返回 UP。前端默认使用 5174，并将 `/api` 去掉后代理到 8080。端口不同可用 SERVER_PORT 配置后端，并在前端启动进程设置 VITE_API_PROXY_TARGET。

登录 API 先 GET `/auth/csrf` 保存 Cookie，用返回 token 发 POST `/auth/login`，请求头 X-XSRF-TOKEN，JSON 包含 username/password。保留登录 Cookie 后 GET `/auth/me` 验证身份。仅健康检查成功不代表登录配置和初始化数据正确。

## 可选 LangGraph 的独立初始化

由数据库管理员创建专用检查点数据库和迁移/运行账号；不复用业务数据库。开发示例的数据库名可以是 `workflow_graph`，用 PostgreSQL 的 `createdb -h 127.0.0.1 -U <管理员> -O <检查点所有者> workflow_graph` 创建一次；已存在时先检查用途，不删除重建。不要给 Python 业务数据库凭据。

在环境中设置检查点迁移连接 WORKFLOW_LANGGRAPH_CHECKPOINT_DSN 后，在本仓库执行：

```sh
uv sync --project agent-runtime-langgraph --frozen
uv run --project agent-runtime-langgraph python -c "import os; from workflow_agent_runtime.postgres import initialize_checkpoint_store; initialize_checkpoint_store(os.environ['WORKFLOW_LANGGRAPH_CHECKPOINT_DSN'])"
```

该函数运行官方 PostgreSQL saver 的 schema/setup，支持重复检查，不能用手工复制其建表 SQL 替代。迁移完成后 Worker 使用受限运行连接；运行账号需要 saver 对象的数据读写权限，不应拥有建库/DDL 权限。权限按实际部署角色配置。

Java 与 Python 的图 Token、artifact 指纹、loopback/HTTPS、共享资源绑定以及新版本发布步骤见 [图派发手册](graph-dispatch-host.md) 和 [版本运行引擎手册](version-runtime-and-call-reconciliation.md)。这些文档前部的早期进度说明可能已过时，最新交付证据以 [2026-11-28 整合记录](../quality/branch-integration-20261128.md) 为准；不要因此跳过配置或扩大已验收范围。

```sh
uv run --project agent-runtime-langgraph python -m workflow_agent_runtime
```

仅启动 Worker 不会切换旧版本，业务版本必须显式冻结 LANGGRAPH 和部署指纹。Worker 不自动初始化检查点表，Python 不保存模型 API Key。

## 验证与维护

- Java 全量：`mvnw verify`；需要 Docker 或明确配置的一次性数据库，不能给测试传日常业务库。
- Python：在 agent-runtime-langgraph 目录 `uv run ruff check .`、`uv run ruff format --check .`、`uv run pytest -q`。数据库/跨语言测试参考 scripts/test-langgraph-runtime.ps1；不提供隔离库导致的跳过不能算验收通过。
- P-17：scripts/test-p17-local.ps1、scripts/test-p17-graph.ps1 及 CI 中的两套契约验收；真实 Provider 为额外 opt-in，会产生调用费用。
- API 文档：`npm ci`，`npm run api:lint`；不必为了正常 Java 启动安装 Node，只有 API 文档校验用到它。
- 更新依赖、环境变量、迁移或种子数据时，同步更新本指南、.env.example、根项目接手指南；保留新库与旧版本升级验收。
