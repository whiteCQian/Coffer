# R30｜桌面生产 Profile 与数据目录

实现日期：2026-10-08。范围是桌面后端的数据与进程生命周期；Electron 壳、兼容 JRE 安装包、工作副本用户流程和完整加密备份/恢复分别继续由 R31–R35 验收。

## 生产配置和存储边界

- 只能单独激活 `desktop`，与 `dev`、`test`、`prod` 混用时拒绝启动。监听地址固定为 `127.0.0.1`，即使传入其他监听地址也不会扩大监听范围。
- H2 使用嵌入式文件库，`DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0`，不启用 `AUTO_SERVER`。正常启动追加 `IFEXISTS=TRUE`，避免 JDBC 自动创建丢失的数据库。Spring 关闭连接池和持久化上下文后才释放数据目录进程锁。
- Flyway 启动迁移并验证校验和，不进行 baseline 或 clean；Hibernate 只做 `validate`。H2 Console、DevTools restart/livereload、SQL 打印和生产 OpenAPI 关闭。
- 桌面 JAR 单独构建到 `backend/target/desktop/`，避免复用服务端编译目录导致 MinIO 类混入。产物不含 MinIO SDK、MinIO 配置、存储服务、桶初始化或服务端探测类。桌面存储使用本地适配器。
- MySQL、Redis、MinIO 服务均不参与桌面启动。桌面对话记忆使用 H2；Redis 向量索引仍为默认关闭的可选功能，向量失败保留已有词法检索降级逻辑，不影响本地正文读取。调用 AI 仍需另行配置并验证模型，R30 不提供模型运行时或绕过既有内容授权。

## 数据目录与初始化

默认位置是 `%LOCALAPPDATA%\Coffer`。可通过 `COFFER_DESKTOP_USER_DATA_DIR` 或 `--coffer.desktop.data-directory=<绝对路径>` 指定；后者优先。数据不得放在当前 JAR 的安装目录内；目录链拒绝符号链接和 Windows 重解析目录，路径不能含 H2 参数分隔符。

数据布局：

```text
<用户数据目录>/
  .coffer-desktop.json          # 格式版本、安装 UUID、文件库 UUID
  .coffer-encryption-key        # 本机随机生成的 32 字节 Base64 主密钥
  .coffer-desktop.lock          # 进程锁；文件保留，操作系统释放锁
  coffer-initial-admin-token.txt # 首次管理员令牌；有账号后的下次启动删除
  database/coffer.mv.db         # 单进程 H2 数据库
  library/.coffer-library.json  # 与数据目录及数据库绑定的库标识
  library/users/...            # 按 owner 隔离的正文
  logs/coffer-safe.log          # 沿用 R26 固定事件码日志
```

首次建库必须明确指定 `--coffer.desktop.initialize=true`，且目标只能是不存在或空的目录。程序先取得进程锁，再创建受限目录、独立主密钥、管理员令牌和标识文件。Windows 使用仅目录所有者的继承 ACL，支持 POSIX 的系统使用目录 0700/文件 0600；文件写入使用 CREATE_NEW 和 force。标识文件写入之前的中断会留下不完整目录，后续正常启动和重复初始化均拒绝接管，不自动补造密钥或猜测旧数据归属。

正常启动必须同时找到：数据目录标识、文件库根目录/标识、非空数据库文件和有效的原密钥。库标识包含格式版本 1 与两个 UUID；数据库 `desktop_library_binding` 保存对应标识和由原密钥加密的校验值。已有数据在 Flyway 升级之前先核对绑定与密钥，失败时不会重绑定到新库。

缺失根目录、数据库、正文库、密钥，版本不兼容、库被替换、密钥不匹配或第二进程占用，都使用固定 `COFFER_DESKTOP_*` 错误码与中文恢复提示退出；不向错误或普通日志输出主密钥、数据库原始错误或本机路径。

## 构建和启动

开发者构建需要 Java 17+ 和 Maven；已构建 JAR 运行只需兼容 Java，不执行 Maven/npm，也不启动数据库或缓存服务。兼容 JRE 的随包交付归 R34。

```powershell
mvn -f backend/pom.xml -Pdesktop package

# 首次创建独立的空数据目录；参数只在首次使用
./scripts/start-desktop.ps1 -JavaPath '<Java17+目录>/bin/java.exe' -Initialize

# 后续正常启动，沿用默认数据目录
./scripts/start-desktop.ps1 -JavaPath '<Java17+目录>/bin/java.exe'

# 或直接运行；正常启动不带初始化参数
java -jar backend/target/desktop/coffer-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=desktop
```

脚本支持 `-UserDataDir`、`-JarPath`、`-JavaPath`、`-Port`。进程在前台运行，其退出结束数据库生命周期。R34 再接入桌面壳的单实例启动和关闭流程。原有 `start-all.bat` 继续启动服务端本机 Web 配置。

## 数据库记忆与隐私清理

`ChatMemoryProvider` 依赖 `ChatMemoryStore` 接口：桌面选择 `DatabaseChatMemoryStore`，其他 Profile 继续使用 `RedisChatMemoryStore`。

H2 中的 `chat_memory` 按 owner/session 隔离，保留 LangChain4j 官方消息序列化、工具调用 ID/结果帧、文件 revision 与模型配置版本。每次读写都验证服务端会话与 owner；文件删除/版本变化或模型目标变化时使旧记忆失效。有效期沿用服务端的 7 天，过期读取及按账号定时清理均会删除记录。数据库记忆包含用户内容，属于个人数据和备份保护范围。

删除对话沿用持久化 `memory_deletion` 队列；桌面清理数据库记录，服务端清理对应 Redis 命名空间。清理不要求已删除的会话仍然存在，且不会删除其他 owner 的记忆。

V37/V38 为 H2/MySQL 同步增加记忆表与绑定表，服务端保持原有 Redis 记忆实现，不把旧 Redis 记忆导入桌面库。

## 主密钥与备份合同

主密钥首次初始化后始终从数据目录读取。普通启动不会创建替代密钥；外部 `COFFER_SECRET_KEY` 若已设置，必须与本地文件一致，否则拒绝启动。迁移到另一台机器必须恢复原密钥及匹配的数据库、文件库和标识，不能只拷贝数据库或正文。

`DesktopDataDirectory.requiredBackupEntries()` 将数据库、完整 library（含标识）、数据目录标识、主密钥列为必须一起备份的条目；管理员尚未初始化时还包括初始化令牌。该清单不包含密钥明文，不生成备份包。R35 必须在停写/数据库一致性备份边界下生成静态加密的整套备份，并验证恢复；运行中不得直接复制活动 H2 文件冒充一致性备份。

桌面主密钥轮换时先停止应用、备份整套数据，将新生成的 32 字节 Base64 密钥安全写入原密钥文件，并通过 `COFFER_SECRET_PREVIOUS_KEY` 提供上一把密钥。启动使用旧密钥验证绑定后只将绑定校验值重新加密；其余凭据继续通过 R26 管理员全库轮换入口处理。完成新备份和全库轮换核验后才能移除上一把密钥。错误密钥和缺少上一把密钥不会被当作新库初始化。

## 验收入口和证据范围

本机最终后端 `mvn verify`：419 项，0 失败、0 错误、10 跳过。其中 7 项真实 MinIO 合同测试未启用（本轮未改动 MinIO 适配器），2 项本机符号链接权限限制跳过，1 项打包解析子进程测试需要另行设置 JAR 环境变量。服务端 Redis 合同在 loopback 临时 Redis 8 实例上执行，无持久化，回归结束即停止；桌面用例始终连接不可达 Redis/MinIO 地址。独立桌面 JAR 的 HTTP 与失败启动探针已通过，产物 MinIO SDK/服务端类条目为 0。另行强杀与新 JVM 恢复探针 `prepared`、`metadata`、`rename` 均通过，核对了正文与持久化操作状态。

随后设置 `COFFER_PARSER_JAR` 为最终 desktop JAR，补跑 `IsolatedParserProcessIntegrationTest` 的 2 项测试，均通过（含全量回归中跳过的打包子进程用例）。最终报告汇总仍为 419 项、0 失败、0 错误，剩余 9 跳过仅为上述 7 项 MinIO 和 2 项符号链接用例。

- `DesktopDataDirectoryTest`：首次初始化边界、随机 UUID/稳定密钥、缺失目录/库/数据库/密钥、不完整初始化、替换标识、格式版本、进程锁及默认路径。
- `DesktopProductionIntegrationTest`：真实文件 H2 + 本地适配器启动、重启持久化、工具帧记忆、两 owner 隔离、隐私清理、TTL、模型配置/文件版本失效、独立 JVM 争锁、数据库绑定/错误密钥失败及失败后锁释放。
- `scripts/verify-desktop-profile.ps1 -JavaPath <java.exe>`：运行独立桌面 JAR，将 Redis/MinIO 指向不可达 loopback 端口；验证 readiness、HTTP 管理员初始化/建用户/登录、私有 API、重启账号保留、初始化令牌清理、进程锁、缺失根目录/库/密钥拒绝及 JAR 无 MinIO 依赖。测试进程均由脚本创建、记录并在 finally 停止；测试数据仅保留在被忽略的 `backend/.test-tmp/`。
- `scripts/verify-file-crash-recovery.ps1`：已有崩溃探针改为首次明确初始化，后续使用正常桌面启动，不再手工创建一个未绑定的 library 根目录。
- CI 增加独立 desktop 构建与产物依赖检查；桌面 H2/owner/进程锁测试包含在现有后端回归入口中。本机结果不代表新 CI 已在 GitHub 执行。

本轮使用 Windows 本机隔离目录、独立进程和独立桌面 JAR 验证无 MySQL/Redis/MinIO 服务依赖；不将它冒充另一台干净 Windows 的安装包/JRE 验收。干净机器安装包、真实断电及完整备份恢复仍由 R34/R35/R50 完成。
