# Coffer Web/NAS 生产发布 2026.10.10

本目录提供生产镜像构建、版本化 Compose、初始化与验收工具。当前代码/本机构建验证通过；完整容器安装和目标服务器验收仍需执行。没有有效的目标授权、厂商支持与扫描证据时，不得将本版本登记为“生产交付完成”。

## 目标与先决条件

支持 Linux Docker Engine，Compose 2.24+（包含当前 Compose 5.x），Python 3.10+、OpenSSL、GnuPG，amd64/arm64。建议至少 4 核/8 GB RAM；数据库和对象卷使用本地可持久化文件系统，磁盘容量按文件数量及保留版本估算。此单服务器方案不提供高可用。

安装者提供 DNS 域名、可被客户端信任的 TLS 完整证书链/私钥、MinIO AIStor 生产授权和支持订阅；NAS 可使用私有 CA + VPN/LAN DNS。内网机器必须信任该 CA。不得把 `-k` 或关闭浏览器证书校验作为生产验收。此发布不自动购买授权、签发证书或改动服务器防火墙。

部署前确认 `172.30.44.0/24` 不与现有 LAN/VPN/Docker 路由冲突；必要时一并修改 `.env` 中 `PROXY_SUBNET`、`PROXY_DYNAMIC_RANGE` 与 `PROXY_IP`（固定地址必须在动态分配池之外）。只信任该代理的固定 IP，代理覆盖 X-Forwarded-For，后端不从任意转发头改写连接地址。后端对模型服务的出口位于独立 `model_egress` 网络；数据库、Redis、MinIO 只在 `internal` 数据网络。MySQL 强制 TLS 且 Connector/J 使用 VERIFY_IDENTITY；MinIO 使用 HTTPS 和独立 CA/主机名校验。代理到后端与可选 Redis 仍限于受信任的单宿主私有网络，跨主机扩展须先完成这些链路的 TLS。

## 全新服务器安装

将本发布源码复制到 `/opt/coffer`，所有命令在 `/opt/coffer/deploy` 下执行。使用 root 或具有 Docker 权限的受控运维账号；Docker 权限等同宿主机高权限。首次执行：

```sh
python3 prepare.py --domain files.example.org --bind 10.0.0.20
# 公网部署可选 --bind 0.0.0.0；仍只有 HTTPS 443 发布。
install -o root -g 10001 -m 0440 /安全来源/fullchain.pem tls/fullchain.pem
install -o root -g 10001 -m 0440 /安全来源/privkey.pem tls/privkey.pem
install -o root -g 10001 -m 0440 /安全来源/minio.license secrets/minio.license
cp support-review.example.json evidence/support-review.json
# 由有权限的负责人填写真实合同、支持、扫描参考号、有效日期和两项完整镜像锁值。
sh coffer.sh build --pull backend proxy mysql minio-init
# CA 加密口令与备份加密口令分别管理，均置于部署目录之外。
umask 077
openssl rand -hex 32 > /root/coffer-db-ca-passphrase
openssl rand -hex 32 > /root/coffer-minio-ca-passphrase
python3 internal_tls.py --db-ca-passphrase-file /root/coffer-db-ca-passphrase --minio-ca-passphrase-file /root/coffer-minio-ca-passphrase
python3 preflight.py
sh coffer.sh pull minio
sh coffer.sh up -d --wait --wait-timeout 300
```

必须使用 `coffer.sh` 或 Python 工具加载 **images.lock.env + .env**；不能仅运行裸 `docker compose up`。默认不启动 Redis；可选 `sh coffer.sh --profile redis up -d --wait --wait-timeout 300`。缺少 Redis 时后台显示 WARNING/DEGRADED，SQL 文件检索、上传下载和聊天记忆仍可用。默认关闭向量检索/embedding；启用向量功能必须配置目标模型、运行 Redis，执行受控重建并验证故障时的 SQL 回退。Redis 没有持久卷，不承载会话、业务台账或正文。

MySQL 镜像只在空卷执行 `mysql-init.sh`：`coffer_app` 仅具有 coffer 库的 SELECT/INSERT/UPDATE/DELETE；`coffer_migrate` 具有库范围的迁移 DDL 权限，在一次性 migrate 服务中运行 Flyway。后台应用不持有 root/迁移口令，自动 DDL 关闭且实体校验开启。启动顺序为卷权限初始化、MySQL 健康与迁移、MinIO 健康与 bucket 初始化、backend readiness、HTTPS proxy。迁移或初始化失败时后端/代理不会放行。

`coffer-owned-v2` 为每个独立部署的私有版本化 bucket。初始化创建独立父身份和受限服务密钥，后端仅得到子密钥，可访问该 bucket 的 `users/*` 对象及必要版本信息，不能管理用户/其他 bucket。Web 用户的 `users/<ownerId>/` 隔离由后端鉴权保证；MinIO 服务密钥不会发给 Web 用户。同一 Docker 宿主上的多个部署必须使用不同项目名、代理子网、发布地址和独立 secrets/卷。不得共享另一应用的 bucket/root 凭据。

通过 HTTPS 首次设置管理员，初始化令牌来自 `secrets/admin_setup_token`，在受控终端读取后输入；不会写入发布源码/容器参数。初始化完成后公开注册永久关闭；管理员创建两个普通账号，各自配置并验证模型端点。API 模型凭据在设置页面按账号加密保存；本地模型需显式配置容器可达地址和 allowed-hosts/ports，不能指向开发机 localhost。保存 master_key 并随加密备份恢复，丢失它会导致保存的模型密钥无法解密。

## 自动化验收与证据

优先在隔离的新服务器完整演练，再部署真实目标。自动验收会创建管理员、两个普通测试账号和随机测试文件；密码仅保存在 root-only `evidence/acceptance-state.json`。真实目标执行 `--initialize` 创建测试账号后，若模型尚未验证会停止并提示；为两个账号配置/验证模型后，重跑并省略 `--initialize`。已有业务服务器不得运行初始化或故障演练而没有维护窗口。

```sh
python3 acceptance.py --base-url https://files.example.org --initialize
# 配置测试账号的真实模型后，继续全部核心流程及故障/重启验收：
python3 acceptance.py --base-url https://files.example.org --exercise-failures
# 私有 CA 使用 --ca /安全来源/ca.pem，不跳过 HTTPS 验证。
```

隔离演练可使用 deterministic 模型避免访问外部模型服务。此 override 只供测试，在单独服务器/独立项目和卷中使用；`preflight.py` 会拒绝以测试模式交付生产。先按上面的真实授权/TLS步骤准备目标，再执行：

```sh
export COFFER_COMPOSE_ACCEPTANCE=1
docker compose --env-file images.lock.env --env-file .env -f compose.yaml -f compose.acceptance.yaml up -d --build --wait --wait-timeout 300
python3 acceptance.py --base-url https://files.example.org --ca /安全来源/ca.pem --initialize --fixture-local --exercise-failures
unset COFFER_COMPOSE_ACCEPTANCE
```

脚本验收静态前端、Secure/HttpOnly 会话、上传/list/detail/content、聊天续聊、双账号文件/任务/会话隔离、管理员权限、开发端口/调试路由拒绝、MinIO 跨 bucket/对象前缀及管理权限拒绝、私有匿名策略、真实卷所有者/模式，以及运行账号 DDL 拒绝。故障模式验收 Redis 启停/删除重建时核心流程可用、MinIO/MySQL 停止时 readiness 失败及恢复、整个栈重启后文件/登录会话/对话恢复。检查私有端口必须另从非可信网络机器执行同样探测，不能只用宿主机结果代替外部边界。

验收文件：`evidence/preflight.json` 和 `evidence/acceptance.json`。后者 `redisFailuresRestartRecovery=NOT_RUN` 不算完成。容量 sidecar 以独立 UID 10002 在实际 MinIO 持久卷执行 statfs，只能写入独立容量收据卷；MinIO 数据根 10001:10001、0700，sidecar 不能遍历/读取对象文件，也无网络或凭据。backend 只读容量收据，不挂载 bucket；不再以 `/state` 文件系统空间代替 MinIO 卷。MySQL 使用独立挂载时仍需宿主机磁盘基线报警。不要在 Docker bind secrets 中依赖 Compose `uid/gid/mode`：本发布实际检查宿主文件 root:10001、0440。MySQL leaf 私钥目录由 internal_tls.py 设置为官方镜像检测到的 mysql GID，仅挂载到 MySQL。

生产只开放 HTTPS；不挂载 bucket 到静态 Web 根，不发布 3306/6379/9000/9001/8080/8081/5173。MinIO 控制台关闭。H2、Swagger、Knife4j 生产界面和测试/调试路由关闭或拒绝；Actuator 仅容器私网 8081。代理不开访问日志；模型请求/响应日志关闭，应用继续使用已有正文清洗日志器。检查证据不包含口令、正文和模型密钥。

Web 配额、强口令、限流、会话及 MinIO 卷容量基线详见 [R42 验收](../docs/plans/R42-Web安全与容量验收.md)。必须由宿主防火墙/路由器限制公网入口；Docker ports 配置不能证明宿主机其他进程没有监听端口。SSH/管理服务只能经可信管理网络访问。从非可信网络主机扫描全部 TCP：`nmap -Pn -sT -p- -oX external-ports.xml 实际公网IP`，将报告传回安全的 evidence 目录，再执行 `python3 external_boundary.py evidence/external-ports.xml --expected-ip 实际公网IP --outside-trusted-network`。要求报告两小时内完成、完整 65535 TCP 端口且只有 443 开放；NAS IPv4/IPv6 入口分别检查。该检查依赖操作者如实确认扫描来源，脚本本身无法证明报告来自哪个网络。

## 一致备份、恢复与升级

工具采用持久维护屏障和物理冷备份：先写入 root-only `.maintenance.json`，关闭原容器自动重启并停止全部写入者；MySQL 事件调度器关闭。独立恢复探针使用只读数据库事务，记录全部业务表、所有权及台账摘要，逐版本读取对象验证 SHA-256，并使用模型主密钥验证加密凭据。随后干净停止 MySQL/MinIO，再备份两卷、backend_state、secrets、TLS/internal-tls、配置、版本清单和全部精确镜像。整个 MinIO 卷包括历史版本、删除标记及 `.minio.sys`，不能用只复制当前对象的 mirror 替代。Redis 和容量收据卷不备份。

```sh
umask 077
# 与数据库、MinIO 及两类 CA 口令分别管理，置于部署目录之外。
openssl rand -hex 32 > /root/coffer-backup-passphrase
python3 backup.py --passphrase-file /root/coffer-backup-passphrase
# 在有独立 GitHub 凭据的可信备份工作站执行；仓库必须是私人仓库。
python3 offhost_github.py /安全暂存/coffer-ID.tar.gpg
```

GPG AES256 流式生成静态加密包，备份完整解密校验通过才恢复服务。明文对象/口令不落盘；精确镜像和清单暂存在 root-only 目录，完成后清理。加密密码不得上传，另由密钥保管人离线保存。离机工具仅使用独立 GitHub 凭据，把密文分片写入私人 release，再下载全部字节并校验分片及整包 SHA-256；完成前 release 保持草稿。建议每日备份、保留 7 日/4 周/6 月；删除前保留至少一份已恢复验证的离机副本。后台超过 48h 缺少有效备份收据报警。备份密码轮换须保留旧密码直到对应保留期届满。

隔离恢复：在空白 Linux 服务器放置本发布的 deploy 工具、密文和单独保管的备份口令。复制 `.env.example` 为 `.env`，填写原配置的业务设置以及新的项目名、域名、绑定地址和不冲突的代理子网；不要执行 prepare.py 生成替代凭据。凭据目录应为空。新的目标必须与原卷、网络隔离，同架构且有足够磁盘空间。

```sh
python3 restore.py /异机备份/coffer-ID.tar.gpg --passphrase-file /root/coffer-backup-passphrase
python3 preflight.py
# DNS/证书仍适用于目标时，使用原双账号经 HTTPS 验证下载/所有权/模型凭据。
```

恢复工具先认证密文内清单和原配置、加载保存的精确镜像，再恢复服务凭据及 PKI，绑定一组新的空卷。只启动原 MySQL/MinIO，验证全部表/对象版本/SHA-256/所有权/台账/密钥一致后才清除 SQL 会话、失效向量标记并重建 Redis，最后启动应用。不同 DNS 需要受控替换边缘证书及重新验收 HTTPS；内部存储 CA 和模型主密钥随恢复点整体恢复。`evidence/restore-proof.json` 是存储证明，双账号 HTTPS 检查仍须记录。恢复使用 `.active-compose.json` 中的精确镜像 ID 和新卷名，禁止自行删除该文件。

```sh
# 在当前运行栈升级；先构建候选并完成安全/许可证审查。
python3 upgrade.py --passphrase-file /root/coffer-backup-passphrase --candidate-image coffer/backend:候选发布 --candidate-web-image coffer/web:候选发布
python3 maintenance.py status
# 意外中断/失败后，保持屏障；用已验证的整套恢复点恢复到另一组空卷：
python3 maintenance.py recover /备份/coffer-ID.tar.gpg --passphrase-file /root/coffer-backup-passphrase
```

upgrade.py 在 Flyway 前生成一致备份，候选失败自动恢复全部旧镜像、凭据和新建数据卷，保留部分迁移失败的旧卷。禁止只回退旧 JAR 或 Flyway repair 掩盖部分 DDL。异常中断时工具不会无条件恢复服务；`coffer.sh` 拒绝绕过 `.maintenance.json` 启动。维护令牌只供当前生命周期操作，直接操作 Docker 的 root 管理员仍必须遵守维护窗口。容量、停机时间和应用回归由维护负责人事前核算；当前工具不自动扩容或清理旧卷。

操作和实际演练证据见 [R43 备份、恢复与升级验收](../docs/plans/R43-Web备份恢复与升级验收.md)。

## MinIO 版本、授权、更新与替换

2026-10-10 查询官方注册表：服务固定到 AIStor `RELEASE.2026-09-19T17-05-25Z.hotfix.4ef74f03d6f2`，客户端固定到 `RELEASE.2026-09-19T15-24-59Z`，完整 OCI digest 在 `images.lock.env`。基础构建镜像也固定 digest；不能复用本机 MinIO 二进制，不能使用 latest/edge 自动更新。基础标签仍用于人工识别，拉取按 digest。

官方来源：[AIStor 容器安装与许可证](https://docs.min.io/aistor/installation/container/install/)、[发行记录](https://dl.min.io/aistor/minio/aistor-release/notes/)、[官方 Quay Server 标签接口](https://quay.io/api/v1/repository/minio/aistor/minio/tag/)、[官方 Quay Client 标签接口](https://quay.io/api/v1/repository/minio/aistor/mc/tag/)。旧社区项目 [已归档](https://github.com/minio/minio)，旧二进制不再维护，不满足本项目“受支持”要求。AIStor 使用 MinIO Software License；生产必须有效授权，免费授权不含厂商支持。本项目不宣称现有账号已获授权或该 hotfix 在目标合同中必然受支持；负责人在 `support-review.json` 为精确镜像确认授权范围、支持期限和安全扫描，缺证据时 preflight 拒绝。

每月检查官方 stable/热修复发行说明、厂商支持门户和安全公告；收到 Critical/已被利用漏洞当天隔离受影响功能，24h 内评估，48h 内按支持渠道修复或替换。High 7 日内处置；延期必须有负责人签字、风险控制和截止日期。发布前对所有镜像生成 SBOM/CVE 报告，检查当前公告是否覆盖固定版本、确认来源/摘要，审阅 npm/Maven/镜像第三方许可证与 NOTICE；不要把“已固定摘要”等同于“无漏洞”。更新只通过 PR 同步 lock/Dockerfile、审查授权、隔离构建/完整验收、整组备份后人工发布；禁止容器自行更新或自动追随 latest。`support-review.json` 最多有效 30 天，并不得晚于实际授权/支持期限。

授权/供应商支持终止时：冻结更新并禁止新增生产部署；选择有支持的 S3 服务作为替换，先用当前 MinIO SDK/适配器验证版本化、用户前缀、Put/Get/Copy/Delete versionId、校验和、范围读取与私有鉴权。对象替换必须迁移所有保留版本和 delete markers；**不能假定跨服务 versionId 不变**，先生成版本映射并迁移所有台账/补偿任务引用，再在隔离双账号验收后切换 DNS/凭据。原卷只读保留到备份与恢复演练通过。无法提供映射时禁止原地换 endpoint，可采用新部署 + 受控应用层导入并保留旧版本档案；不能宣称兼容替换已完成。

## 完成登记

必须同时具备：真实目标授权/支持/漏洞与许可证审查、镜像构建与保存、干净服务器一次安装/初始化证据、HTTPS 双账号验收、外部网络探测、Redis 故障/重建与 MySQL/MinIO readiness、重启恢复、异机冷备份和空卷恢复演练。缺少任一项仍为“待目标环境验收”。
