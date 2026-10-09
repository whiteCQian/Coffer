package com.coffer.desktop;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.nio.file.Path;

/** File association may reuse an existing Office process: never infer that launch/return means close. */
@Component @Profile("desktop")
public class WorkCopyOpener {
    public void open(Path workFile) throws java.io.IOException {
        if (!java.awt.Desktop.isDesktopSupported()
                || !java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN))
            throw new java.io.IOException("系统未提供文件关联打开能力，请配置默认应用后重试");
        java.awt.Desktop.getDesktop().open(workFile.toFile());
    }
}
