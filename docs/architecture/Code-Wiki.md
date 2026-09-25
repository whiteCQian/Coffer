# AgentFS Code Wiki（当前版本）

> 本文是当前代码现状快照，文中的单用户、MinIO 与部署说明描述现有实现，不是最终产品要求。最终合同和目标架构见[ADR-001](../decisions/ADR-001-最终产品合同与存储架构.md)、[需求追踪矩阵](../plans/需求追踪矩阵.md)和[最终落地计划](../plans/AgentFS最终落地执行计划.md)。
>
> 更新时间：2026-09-22
> 项目：AgentFS Nexus
> 说明：本文描述当前代码结构和运行架构；Cofferer C01～C16 的阶段性成果见[归档交接文档](../archive/legacy/Cofferer_C01-C16交接文档.md)。

## 1. 系统概览

AgentFS Nexus 是本地单用户 AI 文件管家。后端负责文件生命周期、AI Agent 编排、文档解析、标签确认、对象存储和混合检索；前端负责文件管理、任务进度、搜索、对话和模型设置。

核心业务链路：

```text
FileController / ChatController
        ↓
Application Service
        ↓
Domain + Repository + Infrastructure
        ↓
H2/MySQL、MinIO、Redis、DeepSeek/Qwen-VL
```

## 2. 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.5.13、Java 17 release |
| 编译环境 | JDK 25 |
| 构建 | Maven 3.9.x |
| 持久化 | Spring Data JPA / Hibernate |
| 数据库 | dev/test H2，prod MySQL 8 |
| 数据库迁移 | Flyway V1–V14（H2 / MySQL 双套脚本） |
| 对象存储 | MinIO 8.6.0 |
| Redis | Spring Data Redis + Lettuce；Redis 8 Vector Set |
| Agent | LangChain4j AiServices + `@Tool` |
| 文本模型 | DeepSeek OpenAI 兼容 API |
| 视觉模型 | Qwen-VL / DashScope |
| 前端 | Vue 3、TypeScript、Element Plus、Vite、Pinia |
| 接口文档 | Springdoc OpenAPI + Knife4j |

## 3. 代码结构

### 3.1 后端业务包

```text
backend/src/main/java/com/agentfs/
├── agent/                 # AiAgentService，Agent 组装与调用
├── file/                  # 文件业务边界
│   ├── api/               # FileController、文件 DTO
│   ├── application/      # 上传、查询、生命周期、解析、事件
│   ├── domain/           # FileMetadata、FileStatus、CategoryType
│   └── infrastructure/   # 解析器、Repository、MinIO 路径
├── tag/                   # 标签确认、候选、联想和持久化
├── task/                  # 异步任务、进度和首页总览
├── vector/                # Redis Vector Set、索引协调、上传监听
├── controller/            # 对话、设置、搜索、向量管理等接口
├── service/               # 跨领域基础服务和历史兼容服务
├── memory/                # Redis 会话记忆
├── config/                # Spring、模型、Redis、MinIO、异步配置
├── entity/                # 跨领域实体：模型密钥、日志、向量任务
├── repository/            # 跨领域 Repository
├── tool/                  # Agent 工具
├── dto/                   # 跨领域 API DTO
├── aspect/annotation/     # 模型调用日志切面
├── exception/             # 全局异常和模型异常
└── util/                  # 提示词、截断、代理解包等工具
```

业务边界包是新代码首选位置；`service` 和 `repository` 中的旧类仍被使用，迁移时应逐步进行，避免大范围移动造成 Bean 或测试回归。

### 3.2 前端结构

```text
frontend/src/
├── api/
│   ├── http.ts             # Axios 实例和统一错误处理
│   ├── files.ts            # 文件列表、搜索、上传、生命周期
│   ├── fileTags.ts         # 标签接口
│   ├── tasks.ts            # 任务接口
│   ├── chat.ts             # Agent 对话接口
│   ├── modelCredentials.ts # 模型密钥接口
│   ├── types.ts            # 兼容出口，类型从生成 schema 派生
│   └── generated/schema.ts # OpenAPI 自动生成，禁止手工编辑
├── views/                  # Home、Files、Search、Settings
├── components/             # FileTile、FileDetailDrawer 等
├── layout/                 # 主布局、上传区、对话区、任务区
├── stores/fileView.ts      # Pinia 文件视图状态
├── router/                 # 页面路由
└── utils/                  # 状态和展示格式化
```

## 4. 关键模块

### 4.1 文件应用层

| 类 | 职责 |
|---|---|
| `FileController` | 文件 HTTP 入口，保持接口契约，不编排底层流程 |
| `FileUploadApplicationService` | 上传用例：类型解析、编码修复、路径生成、MinIO 上传、登记任务 |
| `FileLifecycleApplicationService` | 删除、重命名、改分类、失败重试 |
| `FileService` | 列表、详情、分类计数、查询结果组装 |
| `FileResponseAssembler` | 领域对象到 API DTO 的映射，避免 DTO 依赖 Entity |
| `UploadPipelineService` | 解析、摘要、标签、分类和处理状态推进 |
| `AsyncFileProcessor` | 异步处理入口 |
| `FileUploadedEventListener` | 事务提交后触发异步处理 |
| `DocumentParseService` | 统一调用 ParserFactory |
| `MinioStorageService` | 上传、读取、删除、预签名 URL、对象搬移 |

### 4.2 Agent 与工具

```text
ConversationService
  → AiAgentService
  → LangChain4j AiServices
     ├─ FileParsingTool.parseFile
     ├─ TagGenerationTool.generateTags
     ├─ FileSearchTool.searchFiles
     └─ get_recent_uploads
```

`search_files` 的签名和返回格式是 Agent 契约，任何检索实现改造都必须保持不变。代理工具注册需要使用 `ProxyUtils.unwrap` 处理 CGLIB 代理。

### 4.3 解析器

```text
DocumentParseService
  → ParserFactory
     ├─ TxtParser
     ├─ PdfParser（PDFBox）
     └─ WordParser（Apache POI）
```

图片文件由 `VisionModelService` 调用 Qwen-VL；图片描述、分类和标签仍复用现有上传管线。视频能力暂未作为当前稳定业务完成。

### 4.4 标签模块

`TagConfirmationService` 维护标签生命周期，`TagSuggestionService` 提供候选池和自然语言联想。标签确认完成后可触发分类归档事件。

```text
PENDING_CONFIRMATION → CONFIRMED / REJECTED
```

只有 `CONFIRMED` 标签参与正式搜索；拒绝标签不参与召回。

## 5. 业务时序

### 5.1 上传时序

```text
multipart request
  → FileUploadApplicationService
  → FileTypeResolver / FilenameEncodingFixer / PathGenerator
  → MinIO upload
  → @Transactional: FileMetadata + AsyncTask
  → AFTER_COMMIT: FileUploadedEvent
  → vector/index listener + AsyncFileProcessor
  → parse → model → tags/summary/category → COMPLETED
```

MinIO 上传发生在数据库登记之前；如果数据库登记失败，应用会尝试补偿删除 MinIO 对象。模型调用、Embedding 和 Redis 写入不得占用上传登记事务。

### 5.2 Agent 对话时序

```text
POST /api/chat/send
  → 生成或复用 sessionId
  → ConversationService
  → AiAgentService
  → DeepSeek + tools + Redis ChatMemory
  → 保存 ChatMessage
  → reply + sessionId
```

Redis 会话记忆使用 LangChain4j 原生消息序列化，不能退回会丢失工具调用帧的旧自定义实现。

### 5.3 Hybrid 搜索时序

```text
keyword
  ├─ MySQL FULLTEXT ─┐
  ├─ 标签匹配        ├─ 文件级 RRF ─→ FileListResponse
  └─ Redis VSIM ─────┘
```

词法链路在 H2 或 FULLTEXT 异常时降级到 LIKE。向量链路超出 `vector-timeout-ms`（默认 1500ms）或 Redis/Embedding 异常时立即返回词法结果。

Redis 使用原生 Vector Set：

```text
VADD / VSIM / VREM / DEL
```

不使用 `FT.SEARCH`、`FT.CREATE` 或 `FT.DROPINDEX`。

### 5.4 向量重建

`VectorReindexService` 分页读取已完成文件，重新从 MinIO 解析和向量化，写入临时 generation，校验后切换活动 generation。旧 generation 异步清理。删除清理和索引重建任务均持久化在数据库中，Redis 恢复后可以补偿。

## 6. Controller 与 API

| Controller | 主要接口 |
|---|---|
| `FileController` | `/api/files` 列表、详情、上传、删除、重命名、分类、重试 |
| `SearchController` | `/api/files/search` |
| `FileTagController` | `/api/files/tags/confirm`、`reject`、`candidates`、`suggest` |
| `TaskController` | `/api/tasks/{taskId}`、`/api/tasks/overview` |
| `ChatController` | `/api/chat/send` |
| `ModelCredentialController` | `/api/settings/models` 及 provider 子路径 |
| `ModelRuntimeModeController` | `/api/settings/runtime`：当前模式、校验和切换 |
| `ModelRuntimeEndpointController` | `/api/settings/runtime/config`：API / 本地端点配置与连通性测试 |
| `InboxImportController` | `/api/inbox-imports/progress`：批量导入进度 |
| `GovernancePreviewController` | `/api/governance/previews`：创建、重新分析、编辑、跳过、确认和取消预览 |
| `ArchiveOperationController` | `/api/governance/operations`：台账、导出、重试、撤销和补偿 |
| `VectorAdminController` | `/api/admin/vector/reindex` 及任务查询 |
| `HealthController` | `/health` |

统一响应为 `Result<T>`：

```json
{"code":0,"msg":"success","data":{}}
```

OpenAPI JSON：`/v3/api-docs`；Knife4j：`/doc.html`。

## 7. 持久化与基础设施

### 7.1 Flyway

```text
backend/src/main/resources/db/migration/h2/V1__...V14__...
backend/src/main/resources/db/migration/mysql/V1__...V14__...
```

V1–V7 覆盖基础表、索引、模型密钥和向量索引基础能力；V8 增加操作台账，V9 增加收件箱导入记录，V10 增加治理预览，V11 增加治理补偿任务，V12 增加全局模型运行模式，V13 增加模型运行端点配置，V14 增加归档对象名保留表和分配锁。新增数据库结构必须从 V15 开始，同时维护 H2 与 MySQL，不修改已经执行的迁移。

### 7.2 关键表和对象

- `file_metadata`：文件元数据、状态、分类、MinIO 路径、向量索引状态。
- `async_task`：异步处理进度。
- `tag` / `file_tag_mapping`：标签和确认状态。
- `chat_message`：对话消息。
- `model_call_log`：模型调用记录。
- `model_credential`：AES-GCM 加密密钥。
- `vector_cleanup_task`：删除向量补偿。
- `vector_reindex_job`：重建任务进度。
- `inbox_import_record`：收件箱扫描、去重、稳定性和重试状态。
- `governance_preview_batch` / `governance_preview_item`：Dry-run 预览建议，与正式文件数据隔离。
- `archive_operation_batch` / `archive_operation_item`：归档台账、执行阶段和撤销状态。
- `governance_compensation_task`：对象存储和数据库异常后的持久化补偿任务。
- `model_runtime_setting` / `model_runtime_endpoint`：API / 本地模式及三类模型端点。
- `archive_object_name_reservation` / `archive_object_name_allocator_lock`：归档目标名称的并发保留与分配。
- MinIO `files/...`：原始文件和归档对象。
- Redis 会话 key：由 `RedisChatMemoryStore` 管理。
- Redis 向量 key：由 `RedisVectorStore` 和 generation 命名空间管理。

### 7.3 配置开关

```yaml
agentfs:
  embedding:
    enabled: ${AGENTFS_EMBEDDING_ENABLED:false}
  hybrid:
    enabled: ${AGENTFS_HYBRID_ENABLED:false}
    top-k: ${AGENTFS_HYBRID_TOP_K:50}
    rrf-k: ${AGENTFS_HYBRID_RRF_K:60}
    vector-timeout-ms: ${AGENTFS_HYBRID_VECTOR_TIMEOUT_MS:1500}
```

模型密钥不放入配置文件，设置页写入数据库；生产必须设置 `AGENTFS_SECRET_KEY`。MySQL 和 MinIO 通过环境变量提供连接凭据。

## 8. 运行与验证

推荐：

```text
start-all.bat
```

端口：前端 5173、后端 8080、Redis 6379、MinIO 9000/9001。停止：`stop-all.bat`。

本机手工 Maven 示例：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.3'
$mvn = 'C:\Users\98709\.m2\wrapper\dists\apache-maven-3.9.16-bin\5grr65jo27hi51sujmtcldfovl\apache-maven-3.9.16\bin\mvn.cmd'
& $mvn -f backend/pom.xml test
```

前端：

```powershell
cd frontend
npm run api:generate
npm run api:check
npm run build
```

`api:generate` 需要后端运行并能访问 `/v3/api-docs`；生成后检查 schema 差异再提交。

涉及真实 MinIO 的测试需要确认服务根凭据、后端访问凭据和 bucket 权限一致。H2 不支持 MySQL `MATCH ... AGAINST`，测试日志中的 FULLTEXT 失败应由代码降级到 LIKE，不视为生产 FULLTEXT 故障。

## 9. 设计约束

1. 不改变 `search_files` 工具签名、返回格式和 Agent 编排。
2. 不改变现有 REST 路径、multipart 参数和 JSON 字段。
3. 生产表结构只由 Flyway 管理，禁止依赖 Hibernate 自动建表。
4. Embedding 和 Redis 向量写入必须在上传核心事务提交之后执行。
5. `AFTER_COMMIT` 回调的数据库写入要使用正确的事务传播，必要时 `REQUIRES_NEW`。
6. `@Async` 方法必须在独立 Spring Bean 中，不能传递请求结束后失效的 `MultipartFile`。
7. Hybrid 检索必须保留 FULLTEXT → LIKE 降级链路。
8. 当前单实例使用 JVM 读写锁，不要直接改成分布式锁。
9. 不手工编辑 `frontend/src/api/generated/schema.ts`。
10. 模型和基础设施密钥不得提交 Git。
11. 文件治理必须遵循“预览 → 确认 → 归档执行 → 台账 → 撤销”的主链路；旧标签确认旁路仍需在收尾阶段移除或限制。

## 10. 关键文件索引

| 功能 | 文件 |
|---|---|
| 应用入口 | `backend/src/main/java/com/agentfs/AgentfsApplication.java` |
| 全局配置 | `backend/src/main/resources/application.yml` |
| 文件入口 | `backend/src/main/java/com/agentfs/file/api/FileController.java` |
| 上传用例 | `backend/src/main/java/com/agentfs/file/application/FileUploadApplicationService.java` |
| 上传管线 | `backend/src/main/java/com/agentfs/file/application/UploadPipelineService.java` |
| Agent | `backend/src/main/java/com/agentfs/agent/AiAgentService.java` |
| Agent 搜索 | `backend/src/main/java/com/agentfs/tool/FileSearchTool.java` |
| Hybrid 服务 | `backend/src/main/java/com/agentfs/service/HybridSearchService.java` |
| Redis 向量 | `backend/src/main/java/com/agentfs/vector/RedisVectorStore.java` |
| 向量协调 | `backend/src/main/java/com/agentfs/vector/VectorIndexCoordinator.java` |
| 向量重建 | `backend/src/main/java/com/agentfs/service/VectorReindexService.java` |
| 模型密钥 | `backend/src/main/java/com/agentfs/service/ModelCredentialService.java` |
| 治理预览 | `backend/src/main/java/com/agentfs/governance/application/GovernancePreviewService.java` |
| 归档执行与标签替换 | `backend/src/main/java/com/agentfs/governance/application/ArchiveOperationPersistenceService.java` |
| 操作台账与撤销 | `backend/src/main/java/com/agentfs/governance/application/ArchiveOperationQueryService.java`、`ArchiveRollbackService.java` |
| 模型运行模式 | `backend/src/main/java/com/agentfs/model/runtime/` |
| 前端 API | `frontend/src/api/` |
| 生成类型 | `frontend/src/api/generated/schema.ts` |
| 启动脚本 | `start-all.bat` |
