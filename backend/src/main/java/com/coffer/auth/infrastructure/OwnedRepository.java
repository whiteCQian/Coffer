package com.coffer.auth.infrastructure;

import com.coffer.auth.domain.TenantOwnedEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

@NoRepositoryBean
public interface OwnedRepository<T extends TenantOwnedEntity, ID> extends JpaRepository<T, ID> { }
