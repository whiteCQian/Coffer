package com.coffer.auth.infrastructure;

import com.coffer.auth.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    boolean existsByIdAndRoleAndEnabledTrue(Long id, com.coffer.auth.domain.AuthRole role);

    Optional<AppUser> findByUsername(String username);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.id = :id")
    Optional<AppUser> lockById(@org.springframework.data.repository.query.Param("id") Long id);
    boolean existsByUsername(String username);

    @Query("select u.id from AppUser u where u.enabled = true and u.role = com.coffer.auth.domain.AuthRole.USER order by u.id")
    java.util.List<Long> findEnabledOwnerIds();
}
