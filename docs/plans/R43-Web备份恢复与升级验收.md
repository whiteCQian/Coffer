# R43 Web 备份、恢复与升级

验收状态：Docker 本地隔离恢复、失败升级整套回滚、离机密文回读均通过；临时 socket 运维容器已停止并移除。真实 AIStor 授权/支持及独立目标服务器验收仍待完成。

## 一致恢复点

维护工具在改动容器前以独占创建、0600 权限和 fsync 持久化 `.maintenance.json`。关闭原容器重启策略，停止代理、后台、任务、缓存及其他写入者；拒绝同项目残余写入者、其他项目挂载数据卷，以及未关闭的 MySQL 事件调度器。数据库与对象存储此时仍运行，仅独立只读探针读取。停止应用会等待当前事务结束；排队任务及补偿台账本来就在 SQL 中，随快照恢复。

探针使用 repeatable-read 事务，对全部业务表按列名及行摘要排序记录 SHA-256、行数和 owner 分布；记录文件的 id/owner/key/revision/size/content SHA。对 bucket 所有版本及 delete markers 建清单，并实际读取每个正文计算 SHA-256，核对当前文件引用、所有者路径及大小；验证全部 encrypted_* 模型凭据可由主密钥解密，只保存摘要。探针不启动 Spring、Flyway、会话清理或后台任务。整数先按 JSON 协议归一化，避免 JDBC LongNode 与解析后的 IntNode 误判。

清单完成后干净停止 MySQL/MinIO，拒绝被 SIGKILL 的数据服务；归档整个两卷及 backend_state，并将服务/初始化/迁移/探针等精确镜像加入归档。不得单独复制在线 MySQL 卷、只镜像当前 MinIO 对象或只回滚 JAR。停写期无后续接受的业务写入。备份工作目录与恢复空间有预检，但外部卷、磁盘扩容和容量预留仍须运维在窗口前核算。

## 密钥及离机保管

归档同时保存 master_key、数据库运行/迁移/root 密钥、MinIO 子/父/root 凭据、许可证、独立存储 PKI、边缘 TLS、原 .env、镜像锁及配置版本。GPG AES256 密文通过完整解密、归档路径验证、manifest 摘要验证后才被标记 verified。外部 JSON 收据不是恢复授权来源。目录穿越、绝对路径、重复成员、软/硬链接和设备归档一律拒绝。

备份密码在部署目录之外，以 root-only 随机文件保管，与 DB/MinIO/CA 管理密码分离；不包含在载荷中。密文与密码分别保管，密码持有人必须可在主机完全丢失时取得它。建议 7 日/4 周/6 月保留周期，每月进行隔离恢复；密码轮换不提前销毁旧快照所需密码。

`offhost_github.py` 使用独立 GitHub 身份，在私有仓库发布静态密文分片，下载全部分片复核 SHA-256 及整体重组摘要后才发布 release。不会读取或上传恢复密码，亦不会使用生产存储凭据。此方式只有远端下载回读收据通过才算离机副本。初始账号不能创建私有仓库或未授权目的地时，本地档案不能登记为离机验收通过。

## 恢复、升级与中断处理

restore.py 校验认证载荷，加载原镜像，用原网络/服务配置和新服务器 .env 绑定一组空卷，整体恢复 secrets/PKI。不同的既有凭据禁止覆盖。恢复到新卷保留原卷，不提供自动删除。先只启动 MySQL/MinIO，验证全部表、版本、正文、所有权、台账和密钥完全一致；然后撤销所有 SQL 会话、失效向量标记、重建空 Redis 与容量收据，再启动后台和代理。后台任务以原 SQL 台账和租约规则恢复。向量索引可由受控重建恢复，SQL 检索在此期间继续可用。

upgrade.py 在 Flyway 前完成冷备份并保持屏障。候选后台与迁移服务绑定精确镜像 ID；迁移失败恢复全套旧镜像、密钥和新的数据卷，保留部分迁移的失败卷。演练候选 V44 先在 MySQL 创建并插入 upgrade_partial_probe，再查询不存在的表使 Flyway 失败；必须证明确实已提交部分 DDL，随后才执行整套回滚。

任何异常不会自动删除维护屏障或无条件恢复服务。`maintenance.py status` 输出不含令牌的状态，`maintenance.py recover ARCHIVE --passphrase-file KEY` 从认证整套快照恢复后再放行。仅凭原恢复点的版本号，不允许 Flyway repair 或换旧 JAR。所有运行入口使用 coffer.sh/受控 Python CLI；具有 Docker root 管理权的人工操作仍须遵守维护窗口。

## 演练范围与证据

本次两个项目 coffer_r43_source/coffer_r43_restored 在同一 Docker Desktop Linux 主机上，均不发布宿主端口，HTTPS 使用有效主机名校验的测试 CA。对象服务由固定源代码构建的归档社区 MinIO 提供，仅供兼容性演练；生产配置保留受支持的 AIStor、许可证和支持审查门禁。参考源码的 AGPL/NOTICE/对应源码置于参考镜像，不作为生产支持证明。

最终结果保存到 deploy/evidence/r43-drill-report.json；凭据只在被忽略、权限受控的测试状态与加密备份内。还应保存 acceptance.json、restore-proof.json、upgrade-report.json 和离机下载回读收据。RTO 分别计量从恢复开始到 HTTPS 双账号通过、以及失败升级触发到旧快照栈健康；停写升级无后续写入，RPO 数据损失为 0，恢复点年龄单列。小样本测量不代表 NAS 大数据量承诺，目标环境须以自身容量重新测量。

生产完成登记仍需要：真实 AIStor 授权/有效支持与漏洞/许可证审查、独立恢复服务器双账号验证、脱离宿主及生产凭据的已回读密文副本、独立可取得的恢复密码、失败升级回滚记录，以及 R41/R42 目标外部 HTTPS 边界/容量压力验收。

## 2026-10-10 实际结果

- 两个独立 Compose 项目/空卷；HTTPS 测试 CA 主机名校验；没有宿主发布端口。静态前端、A/B/管理员矩阵、私有 bucket/子密钥、卷权限、DB TLS 和运行账号 DDL 拒绝通过。
- 实际 Redis 停止/删除重建、MinIO/MySQL 故障 readiness 与整栈重启后双账号核心流程通过，见 r43-source-acceptance.json。小配额可解释 413 且拒绝后不增加用量、暴力登录 429/Retry-After 通过，见 r42-security-drill.json；两份报告各自的 NOT_RUN 不作通过项。
- 最终恢复点含 47 张表、两个账号的 2 份文件/2 个对象版本和 32 条可认证的加密模型凭据。所有 SHA-256、所有权、台账和主密钥一致；SQL 会话撤销、向量标记失效、Redis 重建。
- 隔离恢复到 HTTPS 双账号通过用时 141.597 秒。实际 Flyway V44 提交部分 DDL 后失败，整套快照回滚 RTO 142.784 秒；持续停写，无已接受写入丢失，升级 RPO 0。失败时恢复点年龄 63.922 秒。此演练仅使用候选后台 V44；候选前端镜像选项已提供，变更前端的真实发布仍需完整目标回归。
- 732,317,297 字节的 GPG 密文于 08:29:19 UTC 完成独立私有 GitHub 存储并全部下载回读，整包 SHA-256 为 00fba032c426c29a9c52ce1bb22f3e03c2916ca12e4eb7f2089bbbea4a8dd749。恢复口令未上传。
- 运维容器已移除、两个隔离栈已停止，旧卷/失败卷及加密备份保留。恢复密码在 .test-tmp/recovery-key-custody 下仅当前 Windows 用户及 SYSTEM 可访问，尚须由密钥保管人移至主机丢失时可独立取得的离线介质。

[已回读的私有密文副本](https://github.com/whiteCQian/coffer-encrypted-backups/releases/tag/checkpoint-1bb804e5-7dbb-48f6-b479-642dec5a779a)。本地详细证明在 deploy/evidence/r43-drill-report.json。Java 恢复证明 4 项回归、Linux 归档/GPG/配置/屏障 7 项测试、生产 Compose 8 项拒绝边界检查通过。恢复业务配置合并与可选 Redis 生命周期补充已验证；测试没有扩大到尚未取得的真实 AIStor 授权环境。
