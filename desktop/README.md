# Coffer Windows 桌面版

Windows x64 安装包包含 Electron、Vue 生产资源、桌面后端 JAR 和经过 SHA-256 校验的 Eclipse Temurin 21 JRE。用户机器不需要安装 Node、Java、Maven、MySQL、Redis 或 MinIO；启动不使用 Vite 或 `start-all.bat`。

## 安装与首次使用

1. 核对安装包与同目录的 `SHA256SUMS.txt`，双击 `Coffer-0.2.1-windows-x64-setup.exe`，选择独立程序目录。安装目录必须为空或已有 Coffer 程序目录，不能是数据库/文件库目录。
2. 启动 Coffer，明确初始化空的数据目录。默认业务数据为 `%LOCALAPPDATA%\Coffer`；窗口缓存/偏好为 `%APPDATA%\Coffer-shell`，均与程序目录分开。可选择已有原数据目录；目录、数据库或密钥缺失时不会建替代空库。
3. 应用会提供一次性的管理员初始化凭据，无需寻找或复制主密钥。创建管理员后，再创建业务账号并切换登录；管理员与业务账号按原授权规则使用不同入口。
4. 本地选择/拖放先展示当前账号的收件箱目标。确认后只复制，外部原件保留；正式入库路径与模型目标继续使用 R31 的确认流程。首次使用 AI 时需配置并验证自己的模型端点；模型不作为后台启动硬依赖。
5. 编辑通过 R33 工作副本进行。外部应用只打开受控 work 文件；先在外部应用保存并关闭文档，再回到 Coffer 提交新版本。原件变化时可另存/放弃，不能自动覆盖。

卸载默认保留业务数据和密钥，重新安装可继续打开。不要把唯一数据目录或密钥当作程序缓存删除。管理员面板已提供整套静态加密备份，启动页可验证并恢复到空的新目录。标准备份必须位于独立设备；明确选择的本机加密快照不算独立介质备份。

## 升级与异常恢复

安装更新前关闭 Coffer。后端发现数据库版本不匹配时停止正常启动：旧结构需在启动页填写备份口令并明确选择“备份后升级数据库”，持有双锁时建立并读回验证整套加密回滚包，再启动 Flyway；包位于 `.upgrade-backups`。迁移或新后端健康失败时自动恢复整套旧数据，中断事务可重放，不能初始化空库替代。成功也保留加密包；它不替代独立设备副本。

后台异常退出会回到启动页，用户可重启恢复台账。正常关闭通过父子管道关闭 Java/H2；强杀实际启动器主进程后，后台检测 EOF 自动退出。启动器只管理自己生成的实例/PID，不接管其他 Java 服务或端口。

## 构建与本机自动验收

### 源码桌面测试一键启动

在仓库根目录双击 `start-desktop-test.bat`，打开当前源码的 Electron 桌面窗口。默认测试业务数据为 `%LOCALAPPDATA%\Coffer-desktop-test`，窗口配置/缓存为 `%APPDATA%\Coffer-shell-desktop-test`，与正式默认目录分开；再次启动继续使用已有测试账号和文件。首次在桌面启动页确认初始化，再创建管理员和业务账号；已有数据需要升级时按界面提示先完成加密备份，脚本不清空或强制初始化数据。

脚本核对生产资源版本、H2 迁移版本及源文件更新时间，缺失/过期时调用同一 `build-desktop.ps1 -Unpacked` 构建；资源完整性由桌面启动器校验。首次构建需要 Node.js 22.12+、npm、JDK 17+ 和 Maven（或后端 wrapper），没有 node_modules 时使用 npm ci 安装锁定依赖；资源就绪后使用捆绑 JRE 与已准备的 Electron，不启动 Vite/MySQL/MinIO/Redis。测试窗口使用独立 shell profile，关闭窗口由原桌面生命周期停止其 Java 子进程。

```powershell
# 强制重建后启动：
.\start-desktop-test.bat -Rebuild
# 只准备资源，不打开桌面：
.\start-desktop-test.bat -PrepareOnly
# 只读检查版本、重建原因和目录，不下载/构建/初始化：
.\start-desktop-test.bat -CheckOnly
# 使用明确的独立测试目录（路径有空格时加引号）：
.\start-desktop-test.bat -DataDirectory "D:\Coffer Test\data" -ShellDirectory "D:\Coffer Test\shell"
```

工具不在 PATH 时可指定 `-NodePath`、`-NpmPath`（npm.cmd）、`-MavenPath`、`-JavaPath`（JDK java.exe）。测试目录不得与 desktop 程序目录重叠，不接受链接/junction；数据与窗口缓存也必须分开。启动失败时批处理窗口保留错误信息，桌面生命周期日志位于测试 shell profile 的 `launcher.log`。

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

脚本检查安装包/资源校验、真实后台归属、普通本地请求 403、原件保留和卸载后数据库/密钥不变；实际 UI 核心闭环需操作者完成并明确填写 PASS，机器基线与结果写入 `acceptance.json`。本轮按用户选择先交付包和脚本，没有把本机隔离测试声明为外机干净 Windows 验收。

安全配置依据 [Electron 安全指南](https://www.electronjs.org/docs/latest/tutorial/security/) 和 [IPC 指南](https://www.electronjs.org/docs/latest/tutorial/ipc)：renderer 禁用 Node、启用 context isolation/sandbox，来源验证及能力令牌由主进程负责。安装/卸载挂点见 [electron-builder NSIS 文档](https://www.electron.build/docs/nsis/)。JRE 来源与许可证见 [Adoptium](https://adoptium.net/temurin/releases/) 及包内 `runtime/legal`。

## 整套恢复与离线维护
R35 使用 H2 SQL 一致性快照和分块 AES-256-GCM 加密。恢复前核对所有文件、原主密钥、账号、全表结构/行指纹与未完成操作，只提交到空的新目录。详见 `docs/plans/R35-桌面备份恢复与升级验收.md`；离线工具为 `desktop-backup.ps1`，口令仅安全提示和 stdin，不通过参数。独立介质验收按用户本轮要求暂不执行。
