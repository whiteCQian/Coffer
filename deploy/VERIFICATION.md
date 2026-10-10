# 本次验证记录（2026-10-10）

状态：本地生产镜像构建、Docker 隔离核心/故障/安全验收、整套恢复/失败升级回滚、离机密文回读通过。**真实 AIStor 授权环境、独立目标服务器及外网边界/峰值容量验收仍待完成。**

## 本次实际 Docker 验证

- Docker Desktop Linux/amd64，Engine 29.8.2。实际构建 backend、静态 web、MySQL wrapper、mc helper、固定源码参考 MinIO、运维工具及真实失败升级候选镜像；后端在 Temurin 17 镜像编译和运行。web 不运行 Vite dev。
- 修复镜像/部署实际联跑发现的问题：Caddy 文件 capability 与 cap_drop 冲突、TLS SNI 健康检查、固定代理 IP 与动态地址池冲突、MinIO 服务密钥长度、mc 精简镜像工具缺失、恢复 tar 父目录只读权限和 JDBC/JSON 整数类型误判。
- coffer_r43_source/coffer_r43_restored 两个独立项目、独立数据卷、无宿主发布端口；参考服务未使用开发机旧 MinIO 二进制。生产配置继续要求受支持 AIStor 精确 digest、真实许可证及有效支持/安全审查。归档社区参考服务只作兼容性演练，不能替代生产支持证明。
- 实际 HTTPS A/B/管理员授权矩阵、静态前端、CSRF/Cookie/安全头/弱口令拒绝、私有 bucket/服务密钥边界、持久卷权限和 MySQL TLS/运行账号 DDL 拒绝通过。Redis 停止/删除重建、MinIO/MySQL 故障 readiness 与整栈重启后的 SQL 记忆/文件/会话通过。
- 1 KiB 账号配额触发可解释 413，拒绝后用量/对象数不变；暴力登录触发 429 和 Retry-After。容量 sidecar 实际独立 UID 无权读/遍历 MinIO 对象目录，可以采集 statfs。可选 Redis 关闭后生命周期入口保持关闭且后台继续就绪。
- 一致冷备份包含 47 张表、两个用户的 2 份文件/2 个对象版本、32 条加密模型凭据、全部服务/主密钥/PKI/配置及精确镜像。空卷恢复全部表、SHA-256、所有权、台账及凭据一致；撤销 SQL 会话并失效向量标记。HTTPS 双账号恢复 RTO 141.597 秒。
- 实际 Flyway V44 已提交部分 DDL 后失败，自动回滚全部快照到另一组空卷，原失败卷保留。回滚后双账号 HTTPS 验证通过。升级 RPO 0（持续停写，无后续接受写入），回滚 RTO 142.784 秒，恢复点年龄 63.922 秒。
- 独立私有 GitHub release 保存 732,317,297 字节 GPG 密文，下载全包 SHA-256 与本地一致，恢复口令未上传。临时 Docker socket 运维容器已停止并移除，两个演练栈已停止，数据/失败卷保留。

证明：deploy/evidence/r43-drill-report.json、r43-source-acceptance.json、r42-security-drill.json、r43-initial-restore-proof.json、r43-upgrade-report.json、optional-redis-lifecycle.json 及 backups/*.offhost.json。报告中 NOT_RUN 只表示对应报告未跑该项，具体通过项分别引用两份实际验收记录。

## 代码与构建检查

- Java 恢复证明 4 项回归通过：所有权/正文/版本/台账/密钥篡改、缺字段、整数归一化及不支持版本拒绝。
- Linux 恢复工具 7 项测试通过：路径穿越/重复/链接/设备归档拒绝、完整组缺项、持久启动屏障、GPG 错误密码/密文篡改拒绝、原业务配置及新服务器绑定恢复、目标数据卷空间不足拒绝。
- 官方 Compose 实际解析与 8 项生产拒绝边界测试通过；Python 脚本编译及 git diff 检查通过。
- 此前 Web/M1/R23 101 项回归，以及 ZIP 导出配额相关 28 项回归通过。Vue/TypeScript 静态生产构建、server JAR 打包通过；既有主 bundle 大小警告仍存在。相关 Redis SQL 生命周期测试已由本次真实 MySQL/Compose 故障验收补充。

## 不能据此登记为生产完成

- 未取得真实 AIStor 许可证、厂商支持合同及完整镜像 SBOM/CVE/第三方许可证审核证据；生产 preflight 仍拒绝缺少真实审查的部署。
- 本次恢复项目共享同一 Docker Desktop 物理主机，不能替代独立服务器/NAS 恢复演练和目标大容量 RPO/RTO 测量。
- 本次不发布宿主端口，没有非可信网络全 TCP 扫描、真实域名公网 TLS、防火墙/NAS IPv4/IPv6 边界证据，也没有目标峰值负载和真实浏览器全部 CSP/PDF/SSE 回归。
- 离机密文已通过，但独立可取得的离线恢复密码需密钥保管人完成介质保管；本地 ACL 目录不等同离线介质。

发布操作见 [生产发布说明](README.md)，具体协议及证据见 [R43](../docs/plans/R43-Web备份恢复与升级验收.md)。
