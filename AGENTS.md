# AI 接手与本地运行

远端 RAG 评测先读 [执行与回传说明](docs/operations/remote-rag-evaluation.md)。使用统一分支和独立批次，保留失败分母；只向回答模型发送问题与检索正文。原保留集已按用户要求入库，不得再称未曝光盲测。

首次安装、配置、建库或启动，先读 [docs/operations/local-development.md](docs/operations/local-development.md)。如果本仓库位于产品根仓库的子模块中，同时阅读根仓库的 `docs/AI-BOOTSTRAP.md` 和 `release-manifest.json`。

运行环境以 pom.xml、Maven Wrapper、agent-runtime-langgraph/pyproject.toml 和 uv.lock 为准。修改配置/依赖/初始化步骤时同步维护运行指南与 .env.example。平台 DDL/必要种子数据统一由 platform-migrations 的版本化 Flyway 迁移管理，不另造手工业务数据 dump；不要修改已执行迁移、重置已有账号密码或把测试夹具导入日常业务库。
