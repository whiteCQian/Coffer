# R13 搜索、Agent、记忆和引用隔离验收

日期：2026-09-26。范围：当前 Web 服务实现；三环境总验收仍以 R51 为准。

## 实现

- Agent 每轮创建绑定服务端 owner/session 的工具包装器，保留 Spring 授权代理。模型工具参数不包含 owner 或 session；模型产生的文件 ID 必须重新查询当前账号数据库记录。
- 关键词、全文失败后的 LIKE、已确认标签、最近 8 条上传、混合检索的异步词法/向量分支均绑定当前启用普通账号。候选集在返回工具前再次查询；管理员、匿名和停用账号不得调用。
- 新增持久化 chat_session 和 V19 迁移。空 session 由服务端创建 UUID；续聊只接受当前账号已存在的 session，外部账号、不存在或格式错误统一 404。单服务进程使用有限数量的锁串行化同一会话完整轮次。
- Redis 记忆使用 OwnerMemoryId 和 chat:memory:v2:{owner}:{session}，每次读取、写入、清除都验证账号和会话。取消本地记忆实例缓存；保留的 ChatMemory 对象不能用于跨账号读写。删除未使用且不受保护的旧适配器。
- 记忆信封记录工具来源的文件版本；删除或版本变化时整份旧记忆失效，下一轮模型不会收到旧工具正文。保留官方消息 codec，工具调用帧可往返。
- 引用仅来自实际工具结果，包含 revision 和受认证的 contentUrl；详情和内容端点重新验证 owner/revision，外部与不存在资源均 404，旧版本 409。前端按文件 ID + revision 区分引用。
- 向量 documentId 改为 fileId:revision:chunkIndex；丢弃旧格式、版本不符、缺失、外部及未完成文件命中。V19 清除旧索引完成标记，后台按账号重建。重命名、重新分析、标签确认/拒绝、归档/撤销使对应旧版本失效。
- Redis generation 校验格式，键始终位于 owner 命名空间；后台 generation 清理显式传播 owner。
- 原文件流在已授权请求线程打开，由 Spring 资源转换器关闭，避免异步流线程缺失账号上下文。

## 自动化证据

后端 JDK 25.0.3 / Maven 3.9.16（release 17），H2 真实 Flyway V1–V19、真实 Repository/授权与 JDBC HTTP 会话；模型、MinIO 和 Redis 使用可观测模拟边界，不向外部发送私有测试内容。

`AgentIsolationIntegrationTest` 覆盖 14 项：双账号关键词/确认标签/最近上传、最近 8 条排序、向量异常与超时降级、恶意向量 ID 与版本、解析越权零存储 IO、会话猜测零模型/Redis IO、真实 AiServices 工具帧无 B 标记、保留记忆对象和原始 Redis 键拒绝、变更/删除后历史记忆清空、匿名/管理员/停用拒绝、并发 owner 恢复、引用 HTTP 401/403/404/409、聊天 HTTP 服务端会话创建、generation 命名空间。

另有 Agent 工具参数与逐次授权、解析过程中版本变化、诊断不进入模型、消息 codec、引用去重、混合排序等测试。

回归命令：

```powershell
mvn -f backend/pom.xml '-Dtest=AgentIsolationIntegrationTest,ChatCitationCollectorTest,HybridSearchServiceTest,RedisChatMemoryStoreTest,AiAgentServiceTest,ToolTest,OwnerAuthorizationIntegrationTest,StorageAuthorizationTest,CredentialIdentityMigrationTest,AsyncTaskServiceTest,TaskProgressApplicationServiceTest,FileOperationServiceTest,ModelCredentialServiceTest,GovernanceCompensationTest,ArchiveRollbackExecutionTest,TagGenerationToolTest' test
```

结果：79 项，0 失败，0 错误。完整 Spring 上下文与 H2 迁移均通过。前端 vue-tsc 与 Vite 构建通过（保留现有 bundle 体积提示）。

后续全量后端回归：275 项，0 失败、0 错误（JDK 25.0.3/Maven 3.9.16）。JUnit 临时目录使用工作区可写目录，以绕过 Windows 沙箱对系统临时目录清理的拒绝；该执行环境修正不改变测试断言。

## 升级与验证边界

- 旧 session 不自动认领，旧 Redis 记忆不导入；升级后开启新对话。原 chat_message 记录不删除。
- 旧向量需后台重建，在此之前关键词/标签检索可用。
- 尚未声称验证真实 MySQL 升级、Redis 8 原生命令、MinIO 网络故障和桌面/NAS 发布环境；这些由对应环境门禁验证。
- 会话串行化覆盖当前单服务部署，多实例部署需共享会话锁后再启用。
