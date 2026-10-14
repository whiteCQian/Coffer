package com.coffer.model.runtime;

import org.springframework.data.jpa.repository.JpaRepository;

/** Global settings contain no owner data and are never exposed directly by controllers. */
public interface AdminEmbeddingSettingRepository extends JpaRepository<AdminEmbeddingSetting, Long> { }
