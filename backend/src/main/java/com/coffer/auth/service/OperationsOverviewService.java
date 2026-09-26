package com.coffer.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.Map;

/** Deliberate cross-owner boundary: fixed SQL, numeric totals only, no caller-supplied filters. */
@Service @RequiredArgsConstructor
public class OperationsOverviewService {
    private final AdminAuthorization authorization;
    private final JdbcTemplate jdbc;
    public Map<String, Long> overview() {
        authorization.requireAdmin();
        return Map.of(
                "pendingTasks", count("select count(*) from async_task where owner_id is not null and status = 'PENDING'"),
                "processingTasks", count("select count(*) from async_task where owner_id is not null and status = 'PROCESSING'"),
                "failedTasks", count("select count(*) from async_task where owner_id is not null and status = 'FAILED'"),
                "pendingCompensations", count("select count(*) from governance_compensation_task where owner_id is not null and status <> 'SUCCEEDED'"),
                "pendingDeletions", count("select count(*) from storage_deletion_task where owner_id is not null and status <> 'SUCCEEDED'"));
    }
    private long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
}
