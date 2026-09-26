package com.coffer.repository;

import com.coffer.entity.ModelRuntimeSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelRuntimeSettingRepository extends com.coffer.auth.infrastructure.OwnedRepository<ModelRuntimeSetting, Long> {
}
