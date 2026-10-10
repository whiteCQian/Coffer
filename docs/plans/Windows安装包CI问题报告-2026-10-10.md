# Windows 安装包 CI 问题报告

日期：2026-10-10，时区 Asia/Hong_Kong。调查对象为提交 `4bba6ad` 和 `9c2f52d` 的 Windows installer job、打包脚本、锁定 Electron 包及本地已有安装包。以下保留原失败证据；后续修复进展在文末登记，历史失败不被改写为成功。

## 结论与影响

原失败提交的阻断发生在 `scripts/build-desktop.ps1:53`：脚本要求 `desktop/node_modules/electron/dist/electron.exe` 已存在，而 Electron 44.7.0 的 npm 包不在 `npm ci` 阶段自动下载该二进制。原 CI 没有显式执行安装器，也没有运行会触发按需下载的 Electron 入口，因此在进入 electron-builder/NSIS 前退出。

这是干净构建环境的依赖初始化缺口，应作为 R03/R34 的发布阻断项。Shell 安全测试实际通过，桌面 Java 编译、前端生产构建、捆绑 JRE 和资源清单校验亦已通过。日志没有证明应用运行时崩溃、安装/卸载破坏数据或数据恢复失败。最终安装包打包、签名与安装测试尚未在这些 CI run 中执行。

## 两次远端证据

- `4bba6ad`：[CI run 38040808796](https://github.com/whiteCQian/Coffer/actions/runs/38040808796)，[Windows job 114180503887](https://github.com/whiteCQian/Coffer/actions/runs/38040808796/job/114180503887)。在香港时间 **2026-10-10 17:18:07**，`build-desktop.ps1:53` 抛出缺少 Electron 二进制错误；进程退出码为 1。
- `9c2f52d`：[CI run 38041641692](https://github.com/whiteCQian/Coffer/actions/runs/38041641692)，[Windows job 114182900344](https://github.com/whiteCQian/Coffer/actions/runs/38041641692/job/114182900344)。在香港时间 **2026-10-10 17:32:02**，同一行、同一错误重复出现。该提交只更新计划，没有修复安装器。
- 两次 run 的 Backend verify、Frontend build、Desktop distribution 均成功，Windows installer 失败使完整 `ci` 失败。独立 production-build 成功不能替代该失败 job。

最新 Windows runner 为 Windows Server 2025（10.0.26100），镜像 `windows-2025-vs2026`，Node 24.21.0、npm 11.19.0、构建 JDK Temurin 17.0.20-101。这些数值来自日志，不作为最低支持配置；最终用户的 Windows 10/11 干净机仍需独立验收。

## 失败链与已通过阶段

1. `npm ci --prefix frontend` / `npm ci --prefix desktop` 已执行并成功安装锁定依赖；不能将错误解释为工作流忘记安装 npm 依赖。
2. `npm test --prefix desktop` 的 3 项全部通过：普通网页/能力令牌代理边界、数据/安装路径保护、资源完整性拒绝。调查时本机以 Node 24.19.0 重跑这 3 项，亦全部通过。
3. `-Pdesktop package` 编译 432 个 Java 源文件并输出 `BUILD SUCCESS`。desktop profile 按配置跳过自身测试编译/执行；不能把该行视为 Windows 上运行完整后端测试。完整 Backend verify 是另一个成功 job。
4. `vue-tsc -b && vite build` 成功，1776 个模块转换完成。bundle 超过 500 kB 的提示是警告，没有导致此次退出。
5. JRE 下载大小/SHA-256 检查通过，资源清单生成与校验输出 `Verified 320 packaged resources, schema V43`。此时的资源清单覆盖后端、JRE 和静态前端；Electron 可执行文件检查是后续独立步骤。
6. 第 53 行检查 `electron.exe` 失败，抛出下面的错误，退出码 1。
7. 第 58 行 electron-builder/NSIS 和后面的最终安装包 SHA256SUMS 生成没有运行；所以没有这些 run 的新 Windows `.exe` 产物证明。

```text
Install locked desktop dependencies with npm ci --prefix desktop first
```

这条提示不准确：`npm ci` 确实已经完成，缺少的是 Electron 二进制初始化步骤。仅重跑同一工作流或再次运行 npm ci，不能从机制上解决问题。

## 根因依据与本机成功原因

项目锁定 `electron=44.7.0`、`electron-builder=26.15.3`。锁文件的 Electron 条目没有 `hasInstallScript`，本地包和 [v44.7.0 官方 npm 元数据](https://raw.githubusercontent.com/electron/electron/v44.7.0/npm/package.json) 都没有自动 postinstall 定义，但提供 `install-electron → install.js` 可执行入口。这个版本要求 Node >=22.12.0，CI 的 Node 24.21.0 满足要求。

[官方安装说明](https://github.com/electron/electron/blob/main/docs/tutorial/installation.md)说明二进制默认在首次运行 Electron 时下载，也支持显式按需安装。[v44.7.0 的入口源码](https://raw.githubusercontent.com/electron/electron/v44.7.0/npm/index.js)在发现二进制缺失时调用 `install.js`；安装器使用包内版本和校验信息下载、解包。项目安全测试只加载代理/路径/资源模块，不启动 Electron，不会触发下载；打包脚本也在 Electron 入口或 builder 运行前就拒绝缺失文件。因此缺少初始化动作形成直接的构建阻断。

本机 `desktop/node_modules/electron/dist/electron.exe` 与 `path.txt` 已存在，脚本前置检查可以通过；干净 hosted runner 没有这一预置状态。这解释了本机包能构建而 CI 失败的环境差异。不能把预置开发机 node_modules 或缓存作为可复现交付的前提。

日志还出现 npm allowScripts 警告，但列出的是 frontend 的 esbuild/vue-demi 和 desktop 的 electron-winstaller，而不是 Electron 的 postinstall。当前证据不支持“Electron postinstall 被 npm 拦截”这一根因解释，也没有 Electron 下载超时/网络错误日志。

## 原有安装包与原失败提交的差异

- 本地仍有 `desktop/dist/Coffer-0.2.0-windows-x64-setup.exe`，244,972,088 字节；本次重新计算 SHA-256 与交付清单一致：`04fbd7915665dd5a65c0aae57d1f1bdbb874b6e2c84cab413644443e8aed8e56`。
- 本地 `.build/resources/manifest.json` 和 `dist/win-unpacked/resources/coffer/manifest.json` 均记录版本 0.2.0、schema **V41**、320 项资源；该包的本机安装/恢复/卸载证据来自 2026-10-09 的 R34/R35 阶段。
- 新 CI 生成的资源 schema 为 **V43**，但还没有走到 installer 打包；“资源同为 320 项”不代表代码或数据库版本相同。
- 原失败提交的 package.json 使用版本 0.2.0。发布修复后的新包时应明确新版本/提交/迁移号和摘要，防止旧、新程序使用同一文件名造成交付混淆。旧包的已通过结果可以保留，但不能登记为最新源码的 Windows 安装与 V41→V43 升级结果。

## 建议修复

优先在 `build-desktop.ps1` 内加入明确的 Electron 依赖准备阶段，放在 JRE 下载、Maven/Vue 构建和资源复制之前；由 CI 与本地命令共用，避免只有工作流能正常构建。

1. 检查锁定 Electron 包及 `install.js` 已安装；包不存在时准确提示 npm ci，而不是将所有缺失情况都归为未装 npm 依赖。
2. 调用已安装包内的 `install.js`，保留其版本/校验逻辑；检查非零退出码，并确认 win32/x64 的二进制存在、版本与锁定版本一致。不要用取消 checksum、直接复制不明 exe 或全局放行所有依赖安装脚本来消除报错。
3. 保留后续资源和二进制拒绝检查，但给出“包缺失”“下载/校验失败”“exe 未产生”的不同错误。可使用官方 `install-electron` 本地入口；若用 npx，应禁止自动下载安装远端同名工具。
4. CI 拆出“依赖/二进制准备、Shell 测试、JRE 准备、后端、前端、资源校验、NSIS 打包、安装包摘要”等可定位步骤，并立即检查每条关键外部命令的退出码。当前合并步骤名称容易把打包前置失败误读为安全测试失败。
5. 缓存只作优化。首次无缓存必须成功，缓存内容应核对平台、架构和锁定版本；二进制缓存损坏时不能将其当作可发布产物。

初次调查建议的核心动作如下；现已由共同准备入口实现，最终代码另包括版本/PE 架构校验：

```powershell
$electronInstaller = Join-Path $desktop 'node_modules\electron\install.js'
if (-not (Test-Path -LiteralPath $electronInstaller)) {
    throw 'Electron npm package missing; run npm ci --prefix desktop'
}
& node.exe $electronInstaller
if ($LASTEXITCODE -ne 0) { throw 'Locked Electron binary preparation failed' }
# 随后检查 exe、平台/架构和版本，再开始耗时构建与打包。
```

## 修复后的关闭条件

- 在干净 Windows runner、没有项目 Electron dist 的情况下安装锁定依赖，显式准备 Electron 并完成 NSIS 打包；完整 ci 各 job 成功，另保留 production-build 成功记录。
- 本地同一构建入口也可从无 Electron dist 状态完成准备；下载/校验失败必须在耗时构建前返回准确错误，不能放行残缺包。
- 对新 `.exe` 重新记录版本、提交、迁移 V43 和 SHA-256；检查包内资源完整性、捆绑 JRE、MinIO/服务端依赖排除及签名实际状态。
- 在独立干净 Windows 实际执行安装、双账号核心/UI、旧库升级、失败回滚、重启及卸载保留。远端 build 成功不能代替这项运行验收。

## 其他已观察到的开放项

CI npm 日志报告 frontend 2 个 high、desktop 8 个 moderate 审计条目，另有旧传递依赖弃用提示。具体依赖/CVE、是否进入最终运行包及可利用条件尚未完成分析；这些提示没有导致此次退出，应独立做依赖审查，不能直接执行带破坏性升级的 audit fix --force。

现有签名配置默认不签名，本次对本地 0.2.0 安装包的 Authenticode 检查实际返回 `NotSigned`；校验和不等同签名。该项、独立干净 Windows 验收及桌面独立介质备份仍按原最终发布门禁保留，与本次 Electron 初始化错误分别跟踪。

原始 CI 日志和运行 JSON 保存在被 Git 忽略的 `.test-tmp/windows-installer-report/`；报告仅引用脱敏步骤、错误与公开 run 链接，不包含 GitHub 凭据、签名日志下载 URL 或用户数据。

## 修复进展（2026-10-10）

- 已新增共同入口 `desktop/scripts/prepare-electron.cjs`，显式调用锁定 npm 包的安装器，保留包内 checksum，验证二进制版本、Windows PE/x64 架构及标准路径。`build-desktop.ps1` 在耗时构建前执行同一准备入口；electron-builder 使用已准备的分发目录。
- CI 分开记录依赖安装、7 项 Shell/准备回归、真实 Electron 准备、安装包构建及最终版本/资源/摘要检查，关键外部命令失败立即停止。
- 本地 7/7 测试通过，覆盖 fresh npm 包缺少二进制、下载进程失败、缺文件/错误版本/错误架构拒绝及原 Shell 安全边界。在独立空目录复制 npm 包元数据（没有 dist），真实下载并检查 Electron 44.7.0 Windows x64 成功，生成 246,302,208 字节二进制；未运行 GUI。
- 新包版本设为 0.2.1，与旧 0.2.0/V41 验收包区分。远端修复结果已核验，见下文；签名、独立干净 Windows 运行和独立介质门禁继续保留。

## 修复关闭证据

修复提交：[90f37da](https://github.com/whiteCQian/Coffer/commit/90f37da51ae903b1c5727748054972bda879ee75)。[完整 ci 38061297300](https://github.com/whiteCQian/Coffer/actions/runs/38061297300) 和 [production-build 38061297396](https://github.com/whiteCQian/Coffer/actions/runs/38061297396) 全部成功；[Windows job 114239936509](https://github.com/whiteCQian/Coffer/actions/runs/38061297300/job/114239936509) 记录显式锁定 Electron 准备、7/7 测试、实际 NSIS、最终版本/资源/摘要检查通过。

香港时间 2026-10-10 22:55:11，日志输出安装包 SHA-256：`ec095e81b4f50b455b3ecaedb7c373eb86ff7e9b4a21fd95e30f42abfefd5330`，对应 `Coffer-0.2.1-windows-x64-setup.exe`。包内 release 0.2.1、schema V43、320 项资源通过完整性检查；Authenticode 为 `NotSigned`。CI 构建没有启用产物上传，此摘要属于该次 runner 构建，不能拿来校验另一台机器重新生成的包。

Electron 首次二进制准备缺口及 Windows CI 构建阻断已修复。CI 不等同独立干净 Windows 安装/UI/升级/回滚运行验收，签名、依赖审查和桌面独立介质仍沿原发布门禁执行。旧失败日志、本机 V41 包与其 SHA-256 保留为历史证据。
