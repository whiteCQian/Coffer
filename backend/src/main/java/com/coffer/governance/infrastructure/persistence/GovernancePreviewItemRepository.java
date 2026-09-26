package com.coffer.governance.infrastructure.persistence;

import com.coffer.governance.domain.GovernancePreviewItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GovernancePreviewItemRepository extends com.coffer.auth.infrastructure.OwnedRepository<GovernancePreviewItem, Long> {

    List<GovernancePreviewItem> findByPreviewIdOrderByIdAsc(String previewId);

    Optional<GovernancePreviewItem> findByPreviewIdAndFileId(String previewId, Long fileId);

    List<GovernancePreviewItem> findByPreviewIdInOrderByIdAsc(List<String> previewIds);
}
