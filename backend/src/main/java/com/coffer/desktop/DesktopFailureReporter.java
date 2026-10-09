package com.coffer.desktop;

import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalysisReporter;

/** The normal privacy logger omits exception text; display only these fixed, safe diagnostics. */
public final class DesktopFailureReporter implements FailureAnalysisReporter {
    @Override public void report(FailureAnalysis analysis) {
        if (analysis.getCause() instanceof DesktopStartupException)
            System.err.println(analysis.getDescription() + System.lineSeparator() + analysis.getAction());
    }
}
