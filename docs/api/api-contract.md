# 前后端 API 契约维护

后端仅在纯 `dev` Profile 下通过 Springdoc 暴露 OpenAPI 文档；prod/desktop 均关闭并拒绝该入口。生成类型时使用隔离的开发实例及开发数据库，避免连接生产数据：

```text
http://127.0.0.1:8080/v3/api-docs
```

前端使用 `openapi-typescript` 生成契约类型，生成文件位于：

```text
frontend/src/api/generated/schema.ts
```

## 更新流程

1. 启动仅监听本机的后端开发服务（`--spring.profiles.active=dev --server.address=127.0.0.1`）。
2. 在 `frontend` 目录执行 `npm run api:generate`。
3. 检查生成文件差异，确认路径、字段、枚举和可空性变化符合预期。
4. 执行 `npm run api:check`，确认生成文件没有未提交差异。
5. 执行前端类型检查和生产构建。

接口 DTO 的 API 类型应通过 `frontend/src/api/types.ts` 的兼容层使用生成
schema；仅属于页面状态或交互状态的类型可以继续手写。

## 变更约束

- 不要直接编辑 `frontend/src/api/generated/schema.ts`。
- 后端新增或删除枚举值时，必须重新生成并检查前端编译结果。
- 后端 DTO 字段变更时，应同时检查接口调用、组件展示和请求参数。
- `multipart/form-data` 和流式接口可以保留手写请求封装，但响应 DTO 仍应复用生成类型。

## R26 错误与运行状态合同

`Result.code=0` 表示成功。错误使用对应 4xx/5xx HTTP 状态码，`Result.code` 与之同步；错误提示仅包含固定的原因与建议，不回显请求值或原始异常。前端仍兼容旧服务的 HTTP 200 业务错误，不能将非零 code 当成成功。

运行状态、聚合告警和密钥轮换 DTO 已由隔离开发实例生成。公开 liveness/readiness 只返回汇总状态；管理员聚合接口及普通用户受限状态接口的权限、采样和配置见 [R26 运行说明](../plans/R26-隐私错误与运行状态验收.md)。

## R31 桌面收件箱确认合同

`GET /api/inbox-imports/progress` 新增 `requiresPathConfirmation`、`awaitingConfirmationCount` 和每项 `targetPath`；桌面稳定快照进入 `AWAITING_CONFIRMATION`，未确认时不会复制正式正文或登记任务。

`POST /api/inbox-imports/{id}/confirm` 的 JSON 请求为 `{ "targetPath": "预览中的完整逻辑路径" }`。必须具有 USER 会话、CSRF 和既有模型目标授权头；服务端重新检查 owner、受控来源及持久化预览路径，目标变更/冲突返回失败，已完成确认幂等返回成功。此接口不接受宿主机来源绝对路径，也不能通过请求指定 owner。

`POST /api/inbox-imports/{id}/retry` 在桌面模式将人工核对项恢复为待确认路径，仍需再次确认。模型未知/敏感内容沿用逐文件授权。详细状态、原件保留与根目录重绑定约束见 [R31 验收](../plans/R31-本地安全导入验收.md)。

## R32 本地治理与恢复合同

桌面直接复用治理、撤销和物理删除台账接口，DTO/路由保持一致。补偿遇到正式状态变化、冲突或不可恢复结果时返回既有 `MANUAL_REVIEW`，不能把冲突恢复记成 `SUCCEEDED`；需要核对后通过已有补偿重试入口处理。启动恢复立即尝试一批有界任务，并继续遵守退避与上限。详细离线、强杀和鉴权读取证据见 [R32 验收](../plans/R32-本地治理撤销与恢复验收.md)。

## R33 工作副本合同

`GET /api/desktop/capabilities` 返回当前账号可使用的 `workCopies` 能力标记。desktop 专用的 `/api/desktop/work-copies` 支持 GET 分页台账、POST `{ "fileId": ... }` 创建；`/{UUID}` 支持 GET 状态及 POST `/open`、`/save`、`/save-as`、`/close`。close 请求为 `{ "action": "KEEP|REPORT_EXIT|CLOSE|DISCARD", "sha256": "当前副本确认摘要" }`；关闭/放弃需要摘要一致。保存沿用模型授权头和原保存意图，另存只登记独立失败文件，需手动重新分析。

所有入口要求 USER 登录及写请求 CSRF，不接受 owner、宿主路径或应用命令。状态响应 `WorkCopyView` 包含受控副本路径、占用/修改标记、可读时的摘要/大小/mtime、恢复文件 ID 和固定错误码；读取不可用时身份字段为空。业务中断以台账状态返回，界面必须展示 `status/errorCode`，不能将 HTTP 成功当成保存成功。DTO 由 dev 的 schema customizer 纳入生成契约，不启用 dev 的原生打开接口。详见 [R33 验收](../plans/R33-工作副本编辑与冲突验收.md)。

## R34 安装版访问与原生桥

安装版后端增加进程级启动令牌，所有访问由主进程代理注入；仍需既有业务登录/CSRF/模型授权。页面 JavaScript 不持有启动令牌，普通本地网页及非受信窗口不能借用桥调用后端。初始化、目录选择、文件选择/拖放和工作副本打开仅提供固定 IPC 方法，检查主窗口、主 frame 和来源。

`/api/desktop/native-inbox/preview` 与 `/commit` 是主进程专用合同，除 USER/CSRF 外必须有独立的 native capability，普通页面的 HTTP 代理明确不透传此能力。来源只接受原生选择/真实 File 的不透明句柄；来源拒绝数据目录、库内对象、链接/junction。当前 owner/目标路径/来源大小、mtime、文件身份及 SHA-256 在确认时重新核对。成功仅发布当前用户 inbox 副本，不自动绕过 R31 正式路径或模型目标确认。UI 的 IPC 选择回执字段与交互状态定义在 `frontend/src/api/desktop.ts`，不将主进程私有路径/能力当作浏览器 API。

安装版与 CLI 的边界、随机端口/进程归属、升级前备份和外机验收见 [R34 验收](../plans/R34-Electron启动器与安装包验收.md)。

## R35 整套维护合同

备份只开放给已登录管理员，并在原生确认后停止后端；恢复只在启动页选择真实备份包与空的新目录。固定 IPC 方法不接受任意命令或浏览器来源路径，口令仅经 IPC/stdin。Java `--coffer-desktop-maintenance` 属于本机 OS 拥有者的离线维护入口，没有普通 HTTP 备份/任意文件接口。

结果区别 `INDEPENDENT_BACKUP_VERIFIED` 与 `LOCAL_SNAPSHOT_VERIFIED`，后者 independent=false；恢复只有整套认证、文件/原密钥/全表指纹及归属校验后才返回 `RESTORED_VERIFIED`。迁移使用加密包和持久化 journal，健康探测需匹配备份 ID；失败或中断恢复整套旧数据，不标记为空库初始化成功。备份/恢复 UI 的回执是 IPC 专用字段，不能把 R26 的作业回执解释为恢复验收。见 [R35 验收](../plans/R35-桌面备份恢复与升级验收.md)。
