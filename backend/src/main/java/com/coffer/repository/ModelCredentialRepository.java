package com.coffer.repository;

import com.coffer.entity.ModelCredential;
import com.coffer.entity.ModelProvider;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelCredentialRepository extends JpaRepository<ModelCredential, ModelProvider> {
}
