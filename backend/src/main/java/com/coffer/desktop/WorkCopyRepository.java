package com.coffer.desktop;

import com.coffer.auth.infrastructure.OwnedRepository;
import org.springframework.data.domain.Pageable;
import java.util.List;

public interface WorkCopyRepository extends OwnedRepository<WorkCopy, String> {
    List<WorkCopy> findByOrderByCreatedAtDescIdDesc(Pageable page);
    List<WorkCopy> findByStatusInOrderByCreatedAtAscIdAsc(List<String> statuses, Pageable page);
}
