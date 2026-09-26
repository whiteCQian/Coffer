package com.coffer.model.runtime;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

@Aspect @Component @Order(-90) @RequiredArgsConstructor
public class ModelSubmissionAspect {
    private final ModelExecutionSnapshotService snapshots;
    private final HttpServletRequest request;
    @Around("@annotation(submission)")
    public Object submit(ProceedingJoinPoint call, ModelSubmission submission) {
        var snapshot = snapshots.capture(request.getHeader("X-Coffer-Model-Version"),
                "true".equals(request.getHeader("X-Coffer-Allow-Sensitive")), submission.value());
        return ModelExecutionContext.with(snapshot, () -> {
            try { return call.proceed(); }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure) { throw new IllegalStateException("任务提交失败"); }
        });
    }
}
