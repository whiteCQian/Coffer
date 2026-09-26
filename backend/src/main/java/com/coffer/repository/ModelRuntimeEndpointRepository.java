package com.coffer.repository;

import com.coffer.entity.ModelRuntimeEndpoint;
import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.model.runtime.ModelRuntimeCapability;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ModelRuntimeEndpointRepository extends com.coffer.auth.infrastructure.OwnedRepository<ModelRuntimeEndpoint, Long> {

    Optional<ModelRuntimeEndpoint> findByRunModeAndCapability(
            GovernanceRunMode runMode, ModelRuntimeCapability capability);
}
