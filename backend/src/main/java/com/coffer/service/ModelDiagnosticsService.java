package com.coffer.service;

import com.coffer.repository.ModelCallLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.*;
import java.time.LocalDateTime;

@Service @com.coffer.auth.service.OwnerOnly @RequiredArgsConstructor
public class ModelDiagnosticsService {
    private final ModelCallLogRepository logs;
    public record Diagnostic(Long id, LocalDateTime time, String status, int inputTokens, int outputTokens,
                             int totalTokens, int retryCount, long durationMs, String errorCategory) {}
    public Page<Diagnostic> list(int page) {
        return logs.findAll(PageRequest.of(Math.max(0, page), 50, Sort.by(Sort.Direction.DESC, "callTime")))
                .map(row -> new Diagnostic(row.getId(), row.getCallTime(), "SUCCESS".equals(row.getStatus()) ? "SUCCESS" : "FAILED",
                        row.getPromptTokens(), row.getCompletionTokens(), row.getTotalTokens(), row.getRetryCount(), row.getResponseTimeMs(),
                        row.getErrorMessage() == null ? null : "MODEL_FAILURE"));
    }
    public void delete() { logs.deleteAllInBatch(); }
}
