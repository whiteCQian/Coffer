# Coffer · AI 文件管家

Coffer 用来保存、整理和查找文件：导入或上传后解析内容、生成摘要和标签，用户确认整理结果后归档，并可从操作记录核对或撤销。

Windows 桌面版是免登录的个人文件库；Web/NAS 是独立部署的多用户服务。各实例的数据、密钥和备份彼此独立。

当前源码、历史测试结果与尚未完成的发布验收分别记录在[进度与问题](docs/进度与问题.md)和[验收索引](docs/验收索引.md)。

## 从哪里开始

- 普通用户：阅读[使用与部署](docs/使用与部署.md)，选择 Windows 或 Web/NAS 章节。
- 开发者：阅读[开发说明](docs/开发说明.md)，了解模块、构建、测试与 API 契约。
- 查看范围：[产品说明](docs/产品说明.md)。
- 确定下一步：[进度与问题](docs/进度与问题.md)。
- 核对过去的结果：[验收索引](docs/验收索引.md)。

这六份文件是工程内的完整说明入口；实现状态只在“进度与问题”维护。

## Windows 桌面用户

安装包包含 Electron、静态前端、后端和捆绑 JRE，无需自行安装 Java、Node、MySQL、Redis 或 MinIO。

首次打开，选择文件库位置并完成两步创建引导；以后直接进入个人文件库。安装程序与业务数据使用不同目录，卸载默认保留数据。

不要把源码测试启动脚本当成正式安装包。首次使用、接管来源文件、工作副本和备份恢复见[Windows 使用](docs/使用与部署.md#windows)。

## Web / NAS 用户与维护者

普通用户使用维护者提供的 HTTPS 地址登录；管理员建立账号并维护服务设置。

正式部署使用 `deploy/` 的静态前端、MySQL、私有对象存储和 TLS 配置。Redis 为可选索引依赖，部署与整套恢复按[Web/NAS 操作说明](docs/使用与部署.md#web-nas)执行。

本地演练、CI 成功和真实目标环境验收分别登记，不互相替代。

## 源码启动

下面命令在 `Coffer` 根目录执行。开发工具与构建前置条件见[开发环境](docs/开发说明.md#development)。

本机 Web：

```powershell
.\start-all.bat
.\stop-all.bat
```

桌面程序与其后端：

```powershell
.\scripts\start-desktop-test.bat
# 只核对资源和版本，不启动程序：
.\scripts\start-desktop-test.bat -CheckOnly
```

仅桌面后端：

```powershell
.\scripts\start-desktop-backend-test.bat -Port 8082
```

桌面测试入口使用独立测试数据目录；Web 启动入口会使用本机配置的服务与数据库。详细位置和参数见[源码测试](docs/使用与部署.md#source-test)。

## 目录导航

- `backend/`：Spring Boot 后端、测试与 Flyway 迁移。
- `frontend/`：Vue 页面、API 客户端与浏览器测试。
- `desktop/`：Electron 启动器、原生桥、安装资源及打包测试。
- `deploy/`：Web/NAS 镜像、Compose 和维护工具。
- `scripts/`：启动、停止、构建、备份及验收入口。
- `assets/`：品牌原始资源。
- `docs/`：五份主题说明，无额外分类子目录。

`data/`、`backend/data/`、`logs/`、`.coffer-runtime/` 保留现有运行位置。`node_modules/`、`target/`、`dist/`、`.build/`、`.test-tmp/` 等服从依赖、构建和测试工具约定。

## 项目外资料

本机学术原件、历史验收原文、UX 证据和整理前快照集中在[资料目录](../Coffer资料/)。它们不属于公开工程文档，也不自动进入 Git。

修改说明时直接更新对应主题；新增阶段测试结果写入验收索引，新问题写入进度文件。
