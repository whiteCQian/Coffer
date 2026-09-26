package com.coffer.repository;

import com.coffer.entity.ModelCredential;
import com.coffer.entity.ModelProvider;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelCredentialRepository extends com.coffer.auth.infrastructure.OwnedRepository<ModelCredential, Long> {
    java.util.Optional<ModelCredential> findByProvider(ModelProvider provider);
    default java.util.Optional<ModelCredential> findById(ModelProvider provider) { return findByProvider(provider); }
    default boolean existsById(ModelProvider provider) { return findByProvider(provider).isPresent(); }
    default void deleteById(ModelProvider provider) { findByProvider(provider).ifPresent(this::delete); }
}
