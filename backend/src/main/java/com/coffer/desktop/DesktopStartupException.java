package com.coffer.desktop;

/** Fixed startup diagnostics never contain host paths, key material or database errors. */
public final class DesktopStartupException extends IllegalStateException {
    public enum Reason {
        PROFILE_CONFLICT("desktop 不能与 dev、test 或 prod 同时启用"),
        INVALID_DIRECTORY("桌面数据目录无效，必须与安装目录分离且不能包含链接或数据库参数字符"),
        NOT_INITIALIZED("桌面数据目录未初始化；首次建库请明确使用 --coffer.desktop.initialize=true"),
        INITIALIZATION_REFUSED("初始化只允许空目录；已有或不完整的数据目录请检查并恢复备份"),
        IN_USE("桌面数据目录正在被另一进程使用，请关闭已有实例"),
        LIBRARY_MISSING("桌面文件库根目录缺失，请恢复原文件库，不会创建替代空库"),
        DATABASE_MISSING("桌面数据库缺失，请恢复数据库，不会创建替代空库"),
        KEY_MISSING("桌面主密钥缺失，请从备份恢复原密钥，不会生成替代密钥"),
        KEY_INVALID("桌面主密钥文件格式无效或与配置不一致，请检查原密钥"),
        BINDING_INVALID("数据库、数据目录与文件库标识不匹配，请恢复同一套备份"),
        FORMAT_UNSUPPORTED("桌面文件库格式版本不受支持，请使用兼容版本或恢复备份"),
        DATABASE_INVALID("桌面数据库验证失败，请检查数据库与迁移备份"),
        KEY_MISMATCH("桌面主密钥无法验证数据库绑定，请恢复原密钥或提供轮换所需的上一把密钥"),
        SETUP_TOKEN_MISSING("首次管理员初始化令牌缺失，请恢复初始化令牌"),
        REBIND_REQUIRED("文件库路径与数据库记录不一致，请核对原库后明确使用 --coffer.desktop.rebind-library=true"),
        REBIND_PENDING("存在未完成的文件操作，不能重绑定文件库；请先完成恢复或人工核对"),
        LIBRARY_CONTENT_MISMATCH("重绑定文件库的正文与数据库指纹不一致，请恢复完整原库"),
        UPGRADE_BACKUP_REQUIRED("数据库结构与安装包不一致，请先完成一致性备份并使用兼容版本；用户数据已保留"),
        UPGRADE_BACKUP_FAILED("升级备份失败，请关闭外部应用并检查空间与权限；数据库尚未迁移"),
        DOWNGRADE_REFUSED("数据库版本较新，不能降级写入，请使用兼容安装包"),
        UPGRADE_RECOVERY_REQUIRED("检测到未完成升级，必须先恢复整套旧数据；不会启动混合数据或创建空库"),
        IO_FAILED("桌面数据目录读写失败，请检查目录权限和可用空间");
        final String message;
        Reason(String message) { this.message = message; }
    }
    private final Reason reason;
    public DesktopStartupException(Reason reason) { super(reason.message); this.reason = reason; }
    public Reason reason() { return reason; }
}
