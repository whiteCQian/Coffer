# Coffer · AI 智能文件管家

> **状态说明：**本 README 的启动步骤描述当前工作区的单用户开发/演示实现，当前仍依赖 MinIO；它不代表最终产品合同。最终交付要求 Windows 桌面、服务器 Web、飞牛 OS NAS 三种形态、多账号隔离和独立数据，并将从最终应用移除 MinIO。请以[最终落地计划](docs/plans/AgentFS最终落地执行计划.md)、[ADR-001](docs/decisions/ADR-001-最终产品合同与存储架构.md)和[需求追踪矩阵](docs/plans/需求追踪矩阵.md)作为目标口径。
>
基于 AI Agent 的智能文件存储管理系统。核心闭环：**文件上传 → 异步 AI 解析/打标/摘要 → 标签人工确认 → 自然语言对话搜索（工具调用 + 会话记忆）**。附带多模态图片理解（Qwen-VL）与文件分类归档。

## 目录结构

```
Coffer/
├── backend/              # 后端 Spring Boot（Java / Maven）
│   ├── src/              # main + test
│   ├── pom.xml
│   └── e2e-sample/       # 端到端上传测试样本
├── frontend/             # 前端 Vue3 + Element Plus（Vite）
│   ├── src/              # 页面 / 组件 / Pinia store / 路由
│   └── node_modules/     # 依赖（缺失时 start-all.bat 首次会自动 npm install）
├── docs/                 # 接口与设计文档、任务清单、SQL
├── start-all.bat         # 一键启动（推荐）
├── stop-all.bat          # 停止后端与前端
└── README.md
```

## 一键启动（推荐）

> 依赖前置：本机已装好 JDK 17+、Node 20+，常驻/可被拉起的 **MySQL(3306)**、**Redis(6379)** 与 **MinIO(9000/9001)**。请通过环境变量提供 MinIO/MySQL 凭据。首次运行时，启动脚本会自动生成本地 `COFFER_SECRET_KEY`。MySQL 请预先建好库 `coffer`；已有安装若使用旧库，可在不入 Git 的 `coffer-local.cmd` 中设置 `COFFER_DB_URL` 指向原库（详见下方「数据持久化说明」）。

在 `Coffer` 根目录**双击**：

| 脚本 | 作用 |
|---|---|
| `start-all.bat` | 检测并拉起本地 Redis + MinIO → 以 **prod 配置启动后端 jar**（8080，连接 MySQL `coffer`（旧安装可由 `COFFER_DB_URL` 指向原库），数据持久化）→ 轮询 `/actuator/health` 至 UP → 启动前端 Vite（5173）→ 自动打开浏览器。首次若 `frontend/node_modules` 缺失会自动 `npm install`（需联网）。 |
| `stop-all.bat` | 停止后端与前端进程（**保留**共享的 MySQL / Redis / MinIO）。 |

脚本为纯 ASCII / CRLF 批处理，任意中文代码页均可运行；JDK、Redis、MinIO、jar、前端目录等路径在 `start-all.bat` 顶部可配置。若本机默认 `java`/`mvn` 版本不对，脚本直接用内置的绝对路径 `JAVA_EXE` 启动 jar，不受 PATH 影响。

启动成功后访问：

- 前端：http://localhost:5173/
- 后端 API / Knife4j 文档：http://localhost:8080/doc.html

### 模型密钥配置

首次启动后进入前端「设置」页面，在「Chat / RAG」配置中填写对话模型和可选的 Embedding 模型；如果两者由同一个 OpenAI 兼容服务提供，可勾选共用 Base URL 和 API Key。Vision 仍单独配置。可先点击对应的「测试」按钮，再点击保存。密钥只会提交到后端，后端使用 AES-GCM 加密后保存到数据库，前端不会回显密钥；保存后需要重新测试当前运行模式。

生产模式需要基础设施凭据。首次运行 `start-all.bat` 时，会创建不纳入 Git 的 `coffer-local.cmd`，其中保存模型密钥数据库的 AES-GCM 主密钥。该文件丢失后无法解密已保存的模型密钥，请妥善备份，且不要替换其中的值：

```powershell
$env:MYSQL_PASSWORD = "replace-with-mysql-password"
$env:MINIO_ROOT_USER = "replace-with-minio-user"
$env:MINIO_ROOT_PASSWORD = "replace-with-minio-password"
```

启动脚本会检查基础设施变量并自动加载本地主密钥；开发环境未设置该主密钥时仍使用仅限本地开发的临时回退值。

> 说明：`start-all.bat` 检测到端口已被占用会跳过对应服务（例如你已经手动跑着 Redis/MinIO），不会重复启动。前端 `node_modules/` 可删除以缩小包体积，下次双击会自动重装。

> **数据持久化说明（重要）**：`start-all.bat` 以后端 `prod` 配置启动，业务数据持久化到 **MySQL 库 `coffer`**；旧安装的 `coffer-local.cmd` 保留原库地址、MinIO bucket 与加密主密钥。数据库结构、索引和升级记录由 Flyway 管理，首次启动新库会自动执行迁移；已有数据库需按文档建立基线。开发环境使用 H2 本地文件库，后端重启后数据保留；测试环境使用独立内存 H2。旧 `docs/archive/sql/fulltext_search.sql` 仅作为历史参考，不再作为正式迁移入口。

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
调用 `POST /api/admin/vector/reindex` 创建异步重建任务，再用
`GET /api/admin/vector/reindex/{jobId}` 查询进度；重建写入临时 generation，校验后切换活动
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
