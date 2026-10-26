# Coffer 项目面试演示截图

Web 版是主讲解线，桌面版补充“本地个人文件库”的启动和核心界面。所有图片都是从项目的真实 Vue 页面或桌面启动器源码界面中截取，示例文件均为虚构演示数据。

## Web 版（建议按此顺序讲解）

1. 首次进入与初始化入口：[`01-web-first-run.jpg`](screenshots/web/01-web-first-run.jpg)
2. 登录：[`02-web-login.jpg`](screenshots/web/02-web-login.jpg)
3. 工作台、上传入口、任务状态与 AI 管家：[`03-web-workbench.jpg`](screenshots/web/03-web-workbench.jpg)
4. 文件详情、AI 摘要、待确认标签及归档操作：[`04-web-file-detail.jpg`](screenshots/web/04-web-file-detail.jpg)
5. 文件库与分类状态：[`05-web-file-library.jpg`](screenshots/web/05-web-file-library.jpg)
6. 首次调用模型前展示内容发送目标并要求授权：[`06-web-model-consent.jpg`](screenshots/web/06-web-model-consent.jpg)
7. 选择“仅保存文件”的上传结果：[`07-web-upload-saved.jpg`](screenshots/web/07-web-upload-saved.jpg)
8. 关键词搜索、自然语言问答与引用文件：[`08-web-chat-citation.jpg`](screenshots/web/08-web-chat-citation.jpg)
9. 独立关键词搜索结果：[`09-web-keyword-search.jpg`](screenshots/web/09-web-keyword-search.jpg)
10. 用户侧模型与运行模式设置：[`10-web-settings.jpg`](screenshots/web/10-web-settings.jpg)
11. 管理员统一配置界面：[`11-web-admin.jpg`](screenshots/web/11-web-admin.jpg)
12. 服务运行状态与告警区域：[`12-web-admin-runtime.jpg`](screenshots/web/12-web-admin-runtime.jpg)

## 桌面版

1. 桌面启动器欢迎页：[`01-desktop-first-run.jpg`](screenshots/desktop/01-desktop-first-run.jpg)
2. 创建/选择本地文件库：[`02-desktop-confirm-library.jpg`](screenshots/desktop/02-desktop-confirm-library.jpg)
3. 桌面文件库列表与状态：[`03-desktop-library-layout.jpg`](screenshots/desktop/03-desktop-library-layout.jpg)
4. 本地文件搜索与问答引用界面：[`04-desktop-ai-search.jpg`](screenshots/desktop/04-desktop-ai-search.jpg)
5. 桌面文件详情与 AI 摘要：[`05-desktop-file-detail.jpg`](screenshots/desktop/05-desktop-file-detail.jpg)

## 演示范围说明

- 当前仓库环境没有可用的 Web 数据库、对象存储和 AI 服务配置，因此本组截图通过本机隔离的演示 API 展示真实前端页面；文件列表、任务状态、管理数据和 AI 回答均为演示数据。问答内容由演示接口返回，不代表真实模型推理或后端集成验收。
- 上传示例只选择了“仅保存文件”；授权弹窗展示后选择取消，没有把示例文件发送到外部模型服务。
- 桌面启动器界面来自项目自带的 HTML/CSS/JS，并通过模拟原生桥接展示；当前工作区的 Windows 文件 ACL 阻止 Electron 沙箱读取项目运行时，所以桌面文件库页面也由 Vue 前端与演示 API 展示，未声称 Electron 安装包已实际启动。
- 适合向面试官说明页面交互、产品流程和功能设计。若需要证明真实端到端联调，请在已配置数据库、对象存储、模型服务和 Electron 权限的环境重新运行项目并录制。
