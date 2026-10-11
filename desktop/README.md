# Coffer Windows 桌面版

Windows x64 安装包包含 Electron、Vue 生产资源、桌面后端 JAR 和经过 SHA-256 校验的 Eclipse Temurin 21 JRE。用户机器不需要安装 Node、Java、Maven、MySQL、Redis 或 MinIO；启动不使用 Vite 或 `start-all.bat`。

## 安装与首次使用

1. 核对安装包与同目录的 `SHA256SUMS.txt`，双击 `Coffer-0.2.1-windows-x64-setup.exe`，选择独立程序目录。安装目录必须为空或已有 Coffer 程序目录，不能是数据库/文件库目录。
2. 启动 Coffer，明确初始化空的数据目录。默认业务数据为 `%LOCALAPPDATA%\Coffer`；窗口缓存/偏好为 `%APPDATA%\Coffer-shell`，均与程序目录分开。可选择已有原数据目录；目录、数据库或密钥缺失时不会建替代空库。
3. 桌面版仅服务于个人，打开即进入自己的文件库，无需创建管理员、注册或登录。程序内不提供账号管理或用户切换。Web/NAS 部署继续使用独立的多用户授权规则。
4. 本地选择/拖放先展示收件箱目标，并明确提示接管会删除源文件。确认后暂存并保留来源；完成正式入库路径确认、元数据事务提交和正文 SHA-256 校验后，删除原路径文件。未确认、入库失败、源文件变化或占用时保留来源，删除失败在导入结果中显示原因。正式入库路径与模型目标继续使用 R31 的确认流程。首次使用 AI 时需配置并验证自己的模型端点；模型不作为后台启动硬依赖。
5. 编辑通过 R33 工作副本进行。外部应用只打开受控 work 文件；先在外部应用保存并关闭文档，再回到 Coffer 提交新版本。原件变化时可另存/放弃，不能自动覆盖。

卸载默认保留业务数据和密钥，重新安装可继续打开。不要把唯一数据目录或密钥当作程序缓存删除。设置页提供整套静态加密备份，启动页可验证并恢复到空的新目录。标准备份必须位于独立设备；明确选择的本机加密快照不算独立介质备份。

## 升级与异常恢复

个人模式保留共享后端的内部库标识及旧 `users/<id>` 存储键，只用于兼容数据库、密钥和已有引用，不提供多用户隔离或切换。已有且仅有一个业务账号的旧库自动沿用原标识；含多个业务账号的旧库会停止打开并提示先备份迁移，避免静默合并或遗失数据。

文件接管记录随数据库持久化。只有正式文件和导入台账已经提交，且正式正文大小/SHA-256 与源文件预览一致时，才删除来源；通过随机同目录临时名称认领原文件并再次校验，可恢复删除中断。删除失败显示来源保留警告，停止自动重试。恢复备份后撤销待执行的外部路径删除授权，需要人工核对。此前按“复制并保留”导入的历史文件不会追溯删除。

安装更新前关闭 Coffer。后端发现数据库版本不匹配时停止正常启动：旧结构需在启动页填写备份口令并明确选择“备份后升级数据库”，持有双锁时建立并读回验证整套加密回滚包，再启动 Flyway；包位于 `.upgrade-backups`。迁移或新后端健康失败时自动恢复整套旧数据，中断事务可重放，不能初始化空库替代。成功也保留加密包；它不替代独立设备副本。

后台异常退出会回到启动页，用户可重启恢复台账。正常关闭通过父子管道关闭 Java/H2；强杀实际启动器主进程后，后台检测 EOF 自动退出。启动器只管理自己生成的实例/PID，不接管其他 Java 服务或端口。

## 构建与本机自动验收

### 桌面程序与后端一键测试启动

在仓库根目录双击 `start-desktop-test.bat`：打开 Electron 桌面窗口，并由桌面启动器自动启动、检查和管理捆绑 Java 后端，就绪后进入应用页面。后端使用 desktop profile、H2、SQL 会话/记忆和本地文件库；不启动 MySQL、MinIO、Redis 或 Vite。后端端口由启动器随机分配并绑定 loopback，不固定占用 8080，也不会接管已有后端。

默认测试数据为 `%LOCALAPPDATA%\Coffer-desktop-test`，窗口缓存/配置为 `%APPDATA%\Coffer-shell-desktop-test`。通过明确的源码测试标志，仅对空测试目录自动初始化数据库、文件库和主密钥；随后直接进入个人文件库。再次运行自动打开已有测试数据；非空目录不会重置，旧结构升级仍需在界面填写备份口令。安装版保留原首次初始化确认规则，不启用源码测试自动初始化。

控制台在桌面和后端就绪后显示实际 Java PID 与 loopback 端口，不显示能力令牌或密码。关闭桌面窗口由原启动器生命周期停止其 Java 后端；再次双击聚焦同一测试窗口。日志位于测试 shell 目录的 `launcher.log`；`test-startup-state.json` 只记录不含令牌的测试就绪/错误状态。已有后端单独测试目录和正式数据目录保持不变。

资源缺失、版本/schema 不一致或后台/前端源文件更新时，脚本调用共同构建入口准备当前静态资源、desktop JAR 与捆绑 JRE；首次构建需要 Node.js 22.12+、npm、JDK 17+ 和 Maven（或 wrapper）。资源就绪后使用已准备的 Electron 与捆绑 Java。路径含空格时加引号，可通过 `-NodePath`、`-NpmPath`、`-MavenPath`、`-JavaPath` 指定开发工具。

```powershell
.\start-desktop-test.bat
.\start-desktop-test.bat -Rebuild
.\start-desktop-test.bat -PrepareOnly
.\start-desktop-test.bat -CheckOnly
.\start-desktop-test.bat -DataDirectory "D:\Coffer Test\data" -ShellDirectory "D:\Coffer Test\shell"
```

### 仅测试后端服务

原后端单独入口保留为 `start-desktop-backend-test.bat`。它使用独立 `%LOCALAPPDATA%\Coffer-desktop-backend-test`，默认 `http://127.0.0.1:8080`，只需要 Java，重建时需要 JDK/Maven；不会打开桌面窗口。保持控制台打开，Ctrl+C 停止。参数 `-Port`、`-JarPath`、`-JavaPath`、`-MavenPath`、`-CheckOnly`、`-PrepareOnly`、`-Rebuild` 沿用；端口占用时拒绝，不终止已有服务。

```powershell
.\start-desktop-backend-test.bat -Port 8082
```

2026-10-11 本机验证：Windows PowerShell 5.1 的完整一键脚本在两次真实 Electron/捆绑 Java 联跑中通过首次自动启动、再次打开保持数据标识/主密钥不变、桌面关闭后 Java 退出。10 项 Node 回归通过，包括空目录自动启动、已初始化/非空目录拒绝重置和安装版不启用测试初始化。原始状态/数据仅保留在被忽略的 `.test-tmp/combined-desktop-*` 中。

同日个人模式修正验证：33 项针对性后端回归通过，覆盖免登录单库、账号接口关闭、Web 权限回归、正式入库后删除来源、来源变化/占用/暂存失败时保留，以及恢复后撤销外部删除授权。真实打包程序通过选择/拖放、入库接管、工作副本保存/下载、后端重启和父进程退出检查；个人库的静态加密备份及空目录恢复通过，核对文件 SHA-256、原密钥、库标识和未完成工作副本。证据保存在本地被忽略的 `.test-tmp/desktop-personal-*.log` 和 `personal-packaged-*`、`personal-backup-*`；测试仅使用隔离目录和样本。

构建需要 Java 17+、Maven 和 Node 22.12+。运行 `npm ci --prefix desktop`，然后：

```powershell
scripts/build-desktop.ps1 -MavenPath <mvn.cmd> -JavaPath <java.exe>
```

`runtime.lock.json` 固定 JRE 的下载地址、大小和校验值；`manifest.json` 固定每个生产资源的路径、大小和 SHA-256。应用启动重新检查资源，不使用开发机绝对路径或 PATH 中的 Java。已配置 `CSC_LINK`/`WIN_CSC_LINK` 时构建开启签名；本轮没有可用凭据，产物未签名，实际状态以 Authenticode 检查为准。

Electron 44 使用按需二进制安装，`npm ci` 本身不保证 `electron.exe` 已下载。构建脚本先调用 `desktop/scripts/prepare-electron.cjs`，显式运行锁定包内安装器，保留嵌入的下载校验，并检查二进制版本与 Windows x64 架构；缺少 npm 包、下载失败或产物不匹配会在 JRE/Java/Vue 构建前拒绝。缓存放在 `desktop/.build/cache/electron`，首次无缓存构建也必须通过；安装包构建不接受外部 `ELECTRON_OVERRIDE_DIST_PATH`。准备过程不启动 Electron GUI。

`npm test --prefix desktop` 验证普通网页拦截、令牌不透传、目录保护与包校验。`test/installed-e2e.cjs` 使用已安装的可执行文件、全新数据目录、捆绑 JRE 和真实业务 API；仅替代文件选择/初始化确认的原生对话框与本地模型响应，不替代业务 API。测试不访问真实账号或文件。

## 干净 Windows 外机验收

将安装包、`SHA256SUMS.txt`、`scripts/verify-desktop-clean-windows.ps1` 复制到外机。该脚本只需要 Windows PowerShell 5.1，不需开发依赖：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\verify-desktop-clean-windows.ps1 -InstallerPath .\Coffer-0.2.1-windows-x64-setup.exe -TestUninstall
```

脚本检查安装包/资源校验、真实后台归属、普通本地请求 403、确认入库后删除样本来源和卸载后数据库/密钥不变；实际 UI 核心闭环需操作者完成并明确填写 PASS，机器基线与结果写入 `acceptance.json`。本轮按用户选择先交付包和脚本，没有把本机隔离测试声明为外机干净 Windows 验收。

安全配置依据 [Electron 安全指南](https://www.electronjs.org/docs/latest/tutorial/security/) 和 [IPC 指南](https://www.electronjs.org/docs/latest/tutorial/ipc)：renderer 禁用 Node、启用 context isolation/sandbox，来源验证及能力令牌由主进程负责。安装/卸载挂点见 [electron-builder NSIS 文档](https://www.electron.build/docs/nsis/)。JRE 来源与许可证见 [Adoptium](https://adoptium.net/temurin/releases/) 及包内 `runtime/legal`。

## 整套恢复与离线维护
R35 使用 H2 SQL 一致性快照和分块 AES-256-GCM 加密。恢复前核对所有文件、原主密钥、账号、全表结构/行指纹与未完成操作，只提交到空的新目录。详见 `docs/plans/R35-桌面备份恢复与升级验收.md`；离线工具为 `desktop-backup.ps1`，口令仅安全提示和 stdin，不通过参数。独立介质验收按用户本轮要求暂不执行。
