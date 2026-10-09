package com.coffer.desktop;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

public final class DesktopFailureAnalyzer extends AbstractFailureAnalyzer<DesktopStartupException> {
    @Override protected FailureAnalysis analyze(Throwable root, DesktopStartupException cause) {
        return new FailureAnalysis("COFFER_DESKTOP_" + cause.reason().name() + ": " + cause.getMessage(),
                "检查原数据目录或恢复同一套备份；不要重复初始化已有数据。", cause);
    }
}
