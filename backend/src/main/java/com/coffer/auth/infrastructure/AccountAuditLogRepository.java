package com.coffer.auth.infrastructure;

import com.coffer.auth.domain.AccountAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccountAuditLogRepository extends JpaRepository<AccountAuditLog, Long> {
    List<AccountAuditLog> findTop100ByOrderByCreatedAtDesc();
}
