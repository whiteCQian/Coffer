# R34 Electron 启动器与安装包验收

日期：2026-10-09。依赖 R30–R33。本轮交付 Windows x64 安装包与外机验收脚本；用户明确选择先交付，不要求本轮提供另一台 Windows/虚拟机。

## 实现

- Electron 44.7.0 / electron-builder 26.15.3 已锁定。生产 Vue、desktop JAR 和官方校验的 Temurin 21 JRE 一起装入资源；启动验证 320 个资源项，不使用 Vite、开发批处理或开发机 JDK 路径。
- 程序、业务数据、窗口缓存各自分离。首次初始化只接受明确选择的空目录；已有根/库/数据库/密钥缺失均保留错误，不创建替代空库。首次管理员凭据通过受控桥读取，主密钥和启动令牌不向 renderer 返回。
- 全局用户窗口单实例；后台使用 OS 随机 loopback 端口，启动令牌、实例 UUID、真实父 PID 与就绪文件共同校验。不会连到同端口的陌生服务。正常关闭通过管道关闭 Java/H2；后台异常退出显示启动页，强杀启动器后 Java 收到 EOF 关闭。
- renderer `nodeIntegration=false`、`contextIsolation=true`、`sandbox=true`，禁用 webview、权限请求、窗口弹出和外部导航。IPC 只暴露固定方法并验证原窗口/主 frame/来源，不能指定任意频道、命令或文件系统读写操作。
- 主进程对自己的窗口请求注入窗口能力，代理到后端时换成启动令牌；两种令牌不暴露给页面。后端继续要求 USER 登录与 CSRF。普通本地网页即使持有业务会话 Cookie 也无法直接调用敏感 API。
- 文件选择和真实 File 拖放使用主进程保存的短期预览 ID，确认必须匹配显示的目标路径和当前 owner。原生导入专用能力不经普通 HTTP 代理透传；来源拒绝链接/junction、不受控文件以及整个业务数据目录/文件库，不能通过选择器重读主密钥或别的账号对象。先复制到当前用户 inbox，后续正式入库沿用 R31 确认与模型授权。
- 工作副本桥只接收 UUID，后端继续执行 R33 owner/path/身份/占用边界。另存/放弃与持久化恢复沿用现有服务。
- NSIS 每用户安装，无管理员提权；目录拒绝已识别的数据库/库或非程序的非空目录。卸载默认保留数据并明确提示。升级前在持有双锁、写模式数据库尚未打开时验证旧绑定并复制/校验数据、库和密钥；失败不迁移，较新数据库拒绝降级。

## 本机证据与边界

后端完整 verify 454 项、0 失败、0 错误、9 跳过；后续原生目录保护定向复验通过。7 项真实 MinIO 合同本轮未启用，2 项符号链接用例受权限限制。新增原生桥/升级备份/启动令牌门禁共 8 项；Node 启动器安全测试 3 项通过。前端类型检查与生产构建通过。

本机安装后的 E2E 记录由 `desktop/test/installed-e2e.cjs` 生成：全新数据目录，PATH 只保留 Windows 系统路径，启动使用捆绑 JRE；真实登录/导入/分析/副本/读取 API，不使用 API mock。模型响应是本地隔离夹具，原生选择和首次初始化对话框由测试驱动确认。测试覆盖普通网页/携带会话 Cookie 的请求拒绝、沙箱配置、换账号旧预览拒绝、非受信窗口 IPC、单实例、后台强杀恢复和真实主进程强杀生命周期。具体 PASS 与保留证据目录以当前本机日志为准。

最终包本机执行结果为 `R34 packaged E2E PASS`，保留证据位于 `desktop/.test-tmp/installer-validation/workspace-final` 的 `e2e-result.json` / `installed-e2e.png`；`uninstall-result.json` 记录程序移除而数据库/密钥保持同一 SHA-256。测试结束后自己的安装和进程已清理，隔离数据仍保留以供复核。新版本提交后重新分析也会按原有规则更新 revision，验证正式正文和版本递增，未把分析后的最终 revision 固定假定为 1。

安装、同版本程序替换/再启动和卸载数据保留通过真实 NSIS 进程验证，不接管用户已有安装。安装包与 SHA256SUMS 见 `desktop/dist`；产物未提交 Git。当前没有代码签名证书/凭据，实际 Authenticode 状态为未签名；构建支持已有签名凭据时启用签名，不把 checksum 当作签名。

当前安装包 `Coffer-0.1.0-windows-x64-setup.exe` 为 244,918,142 字节，SHA-256 为 `ad33ba3404fdfea606152c0d533218008f9149227e3092e208c60e1e5d1add80`。相邻交付目录包含 checksum、外机脚本和使用说明。已加入 Windows 安装包 CI 构建/校验定义，但未提交/未运行远程 CI；自动审批拒绝了未授权的 GitHub Actions 产物上传，改为本地交付和不上传产物的 CI。

另一台干净 Windows 上的“双击安装—初始化—登录—完整闭环”尚未执行。用户选择先交付安装包和 `scripts/verify-desktop-clean-windows.ps1`；该脚本兼容 Windows PowerShell 5.1，不依赖 Java/Node/Maven，并要求操作者实际完成 UI 流程后记录结果。M3 整体与 R35 独立介质加密备份/新机恢复/失败升级回滚仍保持开放。

实现与使用说明见 [桌面 README](../../desktop/README.md)，安全参考 [Electron 官方指南](https://www.electronjs.org/docs/latest/tutorial/security/) 与 [NSIS 官方文档](https://www.electron.build/docs/nsis/)。
