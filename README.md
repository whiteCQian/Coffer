# Coffer · AI 智能文件管家

> **状态说明：**本 README 描述当前工作区的本机 Web 启动方式；它不代表最终产品合同。最终交付要求 Windows 桌面、服务器 Web、飞牛 OS NAS 三种形态、多账号隔离和独立数据。目标架构是桌面版使用本地文件库且不依赖 MinIO，Web/NAS 保留各自独立的 MinIO 对象存储。请以[最终落地计划](docs/plans/AgentFS最终落地执行计划.md)、[ADR-001](docs/decisions/ADR-001-最终产品合同与存储架构.md)和[需求追踪矩阵](docs/plans/需求追踪矩阵.md)作为目标口径。
>
基于 AI Agent 的智能文件存储管理系统。核心闭环：**文件上传 → 异步 AI 解析/打标/摘要 → 标签人工确认 → 自然语言对话搜索（工具调用 + 会话记忆）**。附带多模态图片理解（Qwen-VL）与文件分类归档。

## 目录结构

```
Coffer/
├── backend/              # 后端 Spring Boot（Java / Maven）
│   ├── src/              # main + test
│   ├── pom.xml
├── frontend/             # 前端 Vue3 + Element Plus（Vite）
│   ├── src/              # 页面 / 组件 / Pinia store / 路由
│   └── node_modules/     # 依赖（缺失时 start-all.bat 首次会自动 npm install）
├── docs/                 # 接口与设计文档、任务清单、SQL
├── start-all.bat         # 一键启动（推荐）
├── stop-all.bat          # 停止后端与前端
└── README.md
```

## 一键启动（推荐）

> 依赖前置：JDK 17+、Node 20+；当后端 jar 需要重建时，还需 Maven 3.9.x 和可用的 Maven 依赖缓存/网络。脚本会复用或启动 MySQL(3306)、Redis 8(6379) 与 MinIO(9000/9001)。MySQL、Redis、MinIO 的凭据从环境变量或本地忽略文件读取。

在 `Coffer` 根目录**双击**：

| 脚本 | 作用 |
|---|---|
| `start-all.bat` | 检查 JDK/Node，jar 过期时自动构建；复用或启动 Redis 8 与 MinIO；以 **prod 配置**启动后端并等待 `/actuator/health` 为 UP；启动前端 Vite 并检查首页；最后打开浏览器。服务仅监听 `127.0.0.1`。 |
| `stop-all.bat` | 只停止当前工作区记录的后端/前端 PID，并校验进程路径和启动时间；**保留** MySQL、Redis 与 MinIO。 |

批处理入口调用 `scripts/start-coffer.ps1` 与 `scripts/stop-coffer.ps1`。启动脚本会从 `JAVA_HOME`、`COFFER_JAVA_HOME` 和已安装 JDK 中选择 Java 17+；本机 Java 版本低于要求时不会误用。Redis、MinIO 的非默认路径可通过本地 `coffer-local.cmd` 设置 `COFFER_REDIS_EXE`、`COFFER_MINIO_EXE`、`COFFER_MINIO_DATA` 和 `COFFER_MINIO_CONFIG_DIR`。

首次启动会生成 AES-GCM 主密钥文件 `.coffer-encryption-key`（若 `COFFER_SECRET_KEY` 已配置则沿用），以及一次性管理员初始化令牌 `coffer-initial-admin-token.txt`。在登录页完成管理员初始化后，下一次启动会清除令牌文件。请备份并妥善保管主密钥；丢失后无法解密已保存的模型凭据。运行日志在 `logs/`，受管进程状态在 `.coffer-runtime/`，这些本地文件均已排除在 Git 外。

启动成功后访问：

- 前端：http://localhost:5173/
- 后端 API / Knife4j 文档：http://localhost:8080/doc.html

### 模型密钥配置

首次启动时，从 `coffer-initial-admin-token.txt` 复制令牌并在前端完成管理员初始化；管理员创建普通用户后，普通用户可登录并进入「设置」页面配置模型目标。密钥只会提交到后端，后端使用 AES-GCM 加密后保存到数据库，前端不会回显密钥。

本地启动需要基础设施凭据。可在不纳入 Git 的 `coffer-local.cmd` 中配置，或设置为当前用户/机器环境变量：

```powershell
$env:MYSQL_PASSWORD = "replace-with-mysql-password"
$env:MINIO_ROOT_USER = "replace-with-minio-user"
$env:MINIO_ROOT_PASSWORD = "replace-with-minio-password"
```

启动脚本只读取 `coffer-local.cmd`，不会覆盖该文件；若没有其他来源的 `COFFER_SECRET_KEY`，会在项目根目录单独创建 `.coffer-encryption-key`。本机 loopback 运行默认将 `COFFER_COOKIE_SECURE` 设为 `false`，生产部署必须使用 HTTPS 并启用 Secure Cookie。

> 说明：端口已被占用时，脚本会复用已运行的 Redis/MinIO；8080 和 5173 则分别做健康/API 与前端首页检查。缺少前端依赖时会使用 `npm ci` 或 `pnpm install`，完成后直接用 Node 启动 Vite。脚本不会按进程名批量杀进程。

> **数据持久化说明（重要）**：`start-all.bat` 以后端 `prod` 配置启动，优先使用 `COFFER_DB_URL` 指定的 MySQL 库；未设置时使用本机 `coffer` 库。Flyway 会在启动时运行数据库迁移，旧的无 owner 数据不会分配给新账号，也不会被启动脚本删除。正式迁移前仍需遵循[最终落地计划](docs/plans/AgentFS最终落地执行计划.md)完成快照和旧数据清理审批。测试环境使用独立内存 H2。旧 `docs/archive/sql/fulltext_search.sql` 仅作为历史参考，不再作为正式迁移入口。

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.5.13 / Java 17+（本机以 JDK 25 编译）/ Maven 3.9.x |
| 前端 | Vue 3.5 + TypeScript + Element Plus / Vite |
| AI 编排 | LangChain4j 1.18.1 `AiServices`（`@Tool` 工具 + 原生 `ChatMemory`） |
| 对话模型 | DeepSeek（OpenAI 兼容，chat + streaming） |
| 多模态 | Qwen-VL（DashScope，图片分析） |
| 存储 | MinIO（对象存储 + 预览 URL）；Redis（会话记忆持久化） |
| 数据库 | dev = H2 文件库；test = H2 内存库；prod = MySQL 8 + Flyway + FULLTEXT（ngram 中文检索） |
| 接口文档 | Knife4j / OpenAPI（`/doc.html`） |

### Hybrid 检索运维说明

Hybrid 检索默认关闭；启用时设置 `COFFER_EMBEDDING_ENABLED=true` 和
`COFFER_HYBRID_ENABLED=true`。向量存储使用 Redis 8 原生 Vector Set（`VADD`、`VSIM`、
`VREM`），词法路仍保留 MySQL FULLTEXT → LIKE 兜底。查询向量路默认最多等待 1500ms，
超时会直接返回词法召回结果。

文件删除后的向量清理由数据库任务表持久化并自动重试。Redis 丢失或需要全量恢复时，
调用 `POST /api/vector/reindex` 创建异步重建任务，再用
`GET /api/vector/reindex/{jobId}` 查询进度；重建写入临时 generation，校验后切换活动
generation，不会先清空当前可用索引。

## 手动启动（备用）

> 依赖前置：JDK 17+、Maven 3.9、Node 20+；启动后端前需先启动当前实现使用的 MinIO 与 Redis（配置说明见本 README）。日常使用推荐上方的 `start-all.bat`。最终交付架构以[最终落地计划](docs/plans/AgentFS最终落地执行计划.md)为准。

### 后端（端口 8080；持久化请用 prod，见下方说明）

```bash
cd backend
# 首次需联网拉依赖；已缓存可加 -o
mvn package -DskipTests
# 持久化运行：连接本机 MySQL coffer 库（等价 start-all.bat 的启动方式）
java -jar target/coffer-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
# 本地开发：不加参数走默认 dev（H2 文件库）
java -jar target/coffer-backend-0.0.1-SNAPSHOT.jar
# 健康检查
curl http://localhost:8080/actuator/health   # → {"status":"UP"}
```

### 前端（Vite dev，端口 5173）

```bash
cd frontend
npm install     # 首次
npm run dev     # http://localhost:5173 （/api 已代理到 8080）
```

## 端口一览

| 端口 | 服务 |
|---|---|
| 5173 | 前端 Vite dev |
| 8080 | 后端 API / Knife4j(`/doc.html`) / actuator |
| 9000 / 9001 | MinIO API / 控制台 |
| 6379 | Redis |

## 文档索引

- [项目总览](docs/项目总览.md)
- [需求与范围清单](docs/需求与范围清单.md)
- [架构图与模块关系](docs/架构图与模块关系.md)
- [已知问题与技术债务](docs/已知问题与技术债务.md)
- [最终落地执行计划](docs/plans/AgentFS最终落地执行计划.md)
- [需求追踪矩阵](docs/plans/需求追踪矩阵.md)
- [M0 存量数据保护记录](docs/plans/M0-存量数据盘点与保护记录.md)
- [最终产品合同 ADR-001](docs/decisions/ADR-001-最终产品合同与存储架构.md)
- [选题报告范围变更说明](docs/product/选题报告范围变更说明.md)
- [API 契约](docs/api/api-contract.md)
- [代码 Wiki（当前实现快照）](docs/architecture/Code-Wiki.md)
- [历史方案与交接材料归档](docs/archive/legacy/README.md)
- `backend/src/main/resources/db/migration/mysql/` —— prod MySQL Flyway 迁移脚本（含 FULLTEXT 索引）
- `backend/src/main/resources/db/migration/h2/` —— dev/test H2 Flyway 迁移脚本
