# R42 Web 安全与容量（2026-10-10）

实现已落地。本机相关回归与构建通过；目标 Linux/飞牛 OS 的实际 TLS、网络、容量及攻击演练仍为待验收，不能把配置或 H2 测试登记为外网验收通过。

## 生产配置

- HTTPS 仅发布 443，TLS 1.2/1.3；Caddy 的 HSTS、nosniff、Referrer-Policy、DENY framing、Permissions-Policy 和 CSP 保护生产静态页。CSP 限制脚本/网络到同源，允许 Element Plus 的内联样式及受控 blob 图片/PDF 预览。开发 CORS 只在 dev 激活。
- 服务端 JDBC 会话；登录删除旧会话并分配新 ID/创建时间，Cookie 为 HttpOnly/Secure/SameSite=Lax、浏览器会话 Cookie；JDBC 空闲有效期 30m，prod 绝对有效期 8h。退出、停用及管理员改密继续撤销会话；CSRF 使用 Secure、SameSite=Strict 的双提交 Cookie 和请求头。CSRF Cookie 供前端读取，因此不设置 HttpOnly。
- 保持原 6–16 字符登录合同以便原账号改密；prod 创建、初始化、重置及改密要求 12–16 字符，包含大小写和数字、拒绝常见弱口令片段。前端从 `/api/auth/status` 读取策略。发布旧数据库前，运维应确认旧账号完成强口令重置；不会从密码哈希推测既有密码强度。
- prod 限流窗口持久化到数据库：登录每 IP 10 次/15m、每账号 30 次/15m；初始化、改密/重置、模型验证保留原端点规则。只保存 SHA-256 限流键，重启不清空窗口；20,000 个有效桶上限时拒绝新增键，不逐出其他账号窗口。拒绝返回 429/Retry-After；数据库不可用时不放行认证限流。
- MinIO 私有 bucket/子服务密钥、`users/<ownerId>/` 校验继续使用 R23 授权正文入口；匿名、B 账号和管理员不能直接读取 A 的正文。没有静态 bucket 或公开预签名下载 URL。下载响应仍为 private/no-store，单段 Range/指纹/对象版本绑定合同不变。
- MySQL 强制 require_secure_transport，运行及迁移账号 REQUIRE SSL，Connector/J 验证 CA 与 mysql DNS 身份；MinIO 验证独立 CA 与 minio DNS 身份。数据库/MinIO CA 私钥由不同离线口令加密，应用 master key、两类服务凭据、备份加密口令独立。Compose 不放明文凭据/默认 root/空口令。后端只接收 DB app + MinIO 子密钥及各自信任证书，不接收服务器私钥、root 或迁移密钥。MySQL 包装入口在降权前读取文件 secrets，避免官方 mysql UID 无法读取 root:10001 的宿主 secret。

## 容量、配额与并发

默认值为可配置部署基线，不是目标硬件性能结论：账号 10 GiB、10,000 个物理对象 key，服务共 100 GiB；单对象不超过 32 MiB；账号待处理任务 20 个、领取处理名额 2 个。`.env` 可设置 ACCOUNT_STORAGE_BYTES/TOTAL_STORAGE_BYTES/ACCOUNT_PENDING_TASKS/ACCOUNT_CONCURRENT_TASKS，须依实际目标卷/保留副本/账号数调整。

生产存储端口在写入任何对象前独立提交 SQL 预约，单一全局行锁串行决定额度。所有上传/复制/移动通过该边界；失败、未确定结果、保留副本及等待物理删除的对象继续计费。真正删除并核实整个 key 没有剩余版本后才释放。首启一次性扫描所有账号（含停用账号）全部版本及遗留意图；不能完整核对时禁止新增写入。禁止额外 bucket 写入者和多个独立后端绕过此数据库；保留版本/对象身份冲突仍归原人工台账处理，不以配额维护清理正文。

账号容量/对象数用尽返回 413，服务总配额或实际 MinIO 卷储备不足返回 507，未能核实容量返回 503，待处理/请求并发达到上限返回 429，均有固定中文说明。配额拒绝发生在存储 I/O 前且不会形成新预约；写入结果不确定的失败仍保留预约。相同预约重试不重复计费，已经记录为 STORED 的 key 不允许覆盖。复制/归档/重命名需要容纳过渡副本的空间，可能因峰值超过配额而拒绝。

任务名额在 durable PROCESSING 领取事务中检查，与状态更新同一次提交；额满任务保持 PENDING，后续扫描重试，不丢队列。账号 HTTP 写操作最多 2 个、其中 multipart 上传最多 1 个；服务 HTTP 写操作最多 3 个，在 servlet 解析/临时文件创建前拒绝并发超限。隐私 ZIP 导出独占这 3 个请求名额，生产限 64 MiB 未压缩内容/1,000 个文件/5,000 条元数据，超限返回可解释 413，避免多个 GET 导出填满临时盘。匿名/管理员 multipart 在 CSRF/servlet 解析之前拒绝。线程池满采用拒绝策略，由现有持久失败/恢复流程处理，不在请求线程展开额外模型任务。异步 HTTP 生命周期完成/错误/超时均释放请求名额。

MinIO 容量探针直接 statfs 实际 MinIO 持久卷，每 10s 写入一次无内容/对象名的收据。独立 UID 10002 对 bucket 根无读/遍历权限，无网络/凭据；后台只读独立收据卷。4 GiB 或 10% 触发 WARNING/DEGRADED；新增写入须保持 2 GiB 或 5%（取较大值）安全储备，并扣除探针采样后预约的增长。收据超过 30s、异常或缺失时拒绝写入/降低就绪；不会用后端卷空间替代 MinIO 卷。账号文件页显示当前配额用量及刷新按钮。

## 自动化与目标验收

本机用例包括 SQL 并发双预约只能一个成功、A/B 用量隔离、重启保留预约、删除释放、额度/实际低容量/过期探针拒绝、待处理及并发名额、持久限流窗口、强口令、绝对会话过期、同账号重复上传及异常后释放、匿名 multipart 零解析。MinIO TLS 用本机 HTTPS 夹具实际握手：可信证书成功、错误信任源/主机名不匹配失败。M1 与 R23 的 OwnerAuthorization/AgentIsolation/Privacy/FileController 回归仍验证匿名/A/B/管理员/停用账号授权矩阵。真实 MySQL TLS 与 MinIO 卷权限需目标 Docker 验收。

安装/密钥生成/证书检查见 [生产发布说明](../../deploy/README.md)。在隔离服务器实际跑全量验收：

```sh
# 先完成该服务器的真实授权、TLS、独立项目和卷准备。
export COFFER_COMPOSE_ACCEPTANCE=1 COFFER_COMPOSE_LIMITS=1
docker compose --env-file images.lock.env --env-file .env -f compose.yaml -f compose.acceptance.yaml -f compose.limits.yaml up -d --build --wait --wait-timeout 300
python3 acceptance.py --base-url https://实际测试域名 --ca /安全来源/ca.pem --initialize --fixture-local --limits --exercise-failures --security-stress
unset COFFER_COMPOSE_ACCEPTANCE COFFER_COMPOSE_LIMITS
```

limits override 将账号额度降为 1 KiB，使用 900 字节正文触发可解释 413并确认用量未增长，避免填满真实卷。security-stress 必须最后执行，会暂时阻断该测试 IP 的新登录，不应在日常业务中执行。另需从**非可信网络**探测 443/3306/6379/9000/9001/8080/8081/5173，确认仅预期 HTTPS；实际证书链/期限、MinIO 探针 UID 权限、容器镜像构建与 A/B/管理员全量 M1 在目标服务器都必须留证。

需保存 evidence/preflight.json、acceptance.json（quotaAdmission/bruteForce 必须 PASS，NOT_RUN 不算通过）、镜像构建结果和外部网络探测记录。CSP 的真实浏览器上传/预览/下载/SSE 回归、目标峰值并发/大文件/恢复演练属于目标验收，尚未执行。

技术依据：[Connector/J CA/服务身份验证](https://dev.mysql.com/doc/connector-j/en/connector-j-server-authentication.html)、[MinIO TLS 配置](https://docs.min.io/aistor/installation/linux/network-encryption/enable-tls-encryption/)。
